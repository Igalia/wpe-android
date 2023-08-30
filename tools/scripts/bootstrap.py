#!/usr/bin/env python3

##
# Copyright (C) 2022 Igalia S.L. <info@igalia.com>
#   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
#   Author: Zan Dobersek <zdobersek@igalia.com>
#   Author: Philippe Normand <pnormand@igalia.com>
#   Author: Adrian Perez de Castro <aperez@igalia.com>
#   Author: Jani Hautakangas <jani@igalia.com>
#   Author: Loïc Le Page <llepage@igalia.com>
#
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.
#
# This library is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with this library; if not, write to the Free Software
# Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
##

"""
This script takes care of fetching, building and installing all WPE Android dependencies,
including libwpe, WPEBackend-android and WPEWebKit.

The cross-compilation work is done by Cerbero: https://github.com/Igalia/cerbero.git

After cloning Cerbero's source through git in the `build` folder, the process starts with
the following Cerbero command:

`./cerbero-uninstalled -c config/cross-android-<arch> package -f wpewebkit`

The logic for this command is in the WPEWebKit packaging recipe in Cerbero's repo:
https://github.com/Igalia/cerbero/blob/wpe-android/packages/wpewebkit.package

This command triggers the build for all WPEWebKit dependencies. After that WPEWebKit itself
is built. You can find the recipes for all dependencies and WPEWebKit build in the
`recipes` folder of Cerbero's repo.

Once WPEWebKit and all dependencies are built, the packaging step starts.
The list of assets that are packaged is defined by the `files` variable in the packaging recipe.
The syntax `wpeandroid:libs:stl` means from the recipe wpeandroid, include the libraries
(`files_libs` in the recipe) and the STL lib (`files_stl` in the recipe).
You can think of the `:` separating the file types as commas in a list. For most recipes
we only care about the libraries, except for WPEWebKit from which we want everything.

The packaging step results in two different tar files. One containing the runtime assets
and another one with the development assets. The content of these tar files is extracted
in the `build/sysroot/<arch>` folder.

After that we are done with Cerbero and back into the bootstrap script.

Before being able to use the generated libraries, we need to work around a limitation of
Android's package manager. The package manager only unpacks libxxx.so named libraries so
any library with versioning (i.e. libxxx.so.1) will be ignored. To fix this we rename all
versioned libraries to the libxxx.so form. For example, a library named libfoo.so.1 will
become libfoo_1.so. Apart from renaming the actual library files, we need to tweak the
SONAME and NEEDED values as well to reflect the name changes. We also need to take care of
the symbolic links to reflect the naming changes.

The final step is to copy the needed headers and processed libraries into its corresponding
location within the `wpe` project. This is done by the `install_deps()` function.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import fileinput
from pathlib import Path
from textwrap import dedent
from urllib.request import urlretrieve


class Bootstrap:
    default_arch = "arm64"
    default_version = "2.40.5"

    _cerbero_origin = "https://github.com/Igalia/cerbero.git"
    _cerbero_branch = "wpe-android"

    _packages_url_template = "https://wpewebkit.org/android/bootstrap/{version}/{filename}"
    _devel_package_name_template = "wpewebkit-android-{arch}-{version}.tar.xz"
    _runtime_package_name_template = "wpewebkit-android-{arch}-{version}-runtime.tar.xz"

    # These are the libraries that the glue code link with and that are required during build
    # time. These libraries go into the `imported` folder and cannot go into the `jniLibs`
    # folder to avoid duplicated libraries.
    _build_libs = [
        "libgio-2.0.so",
        "libglib-2.0.so",
        "libgmodule-2.0.so",
        "libgobject-2.0.so",
        "libwpe-1.0.so",
        "libWPEBackend-android.so",
        "libWPEWebKit-1.0_3.so"
    ]
    _build_includes = [
        ("glib-2.0", "glib-2.0"),
        ("libsoup-2.4", "libsoup-2.4"),
        ("wpe-1.0", "wpe"),
        ("wpe-android", "wpe-android"),
        ("wpe-webkit-1.0", "wpe-webkit"),
        ("xkbcommon", "xkbcommon")
    ]
    _soname_replacements = [
        ("libnettle.so.8", "libnettle_8.so"),  # This entry is not retrievable from the packaged libnettle.so
        ("libWPEWebKit-1.0.so.3", "libWPEWebKit-1.0_3.so")  # This is for libWPEInjectedBundle.so
    ]
    _base_needed = ["libWPEWebKit-1.0_3.so"]

    def __init__(self, args=None):
        args = args or {}
        if not isinstance(args, dict):
            args = vars(args)

        self._arch = args["arch"] if "arch" in args else self.default_arch
        self.default_version = args["version"] if "version" in args else self.default_version
        self._external_cerbero_build_path = args["cerbero"] if "cerbero" in args else None
        self._build = args["build"] if "build" in args else False
        self._debug = args["debug"] if "debug" in args else False
        self._wrapper = args["wrapper"] if "wrapper" in args else False

        if self._external_cerbero_build_path:
            self._external_cerbero_build_path = os.path.realpath(self._external_cerbero_build_path)
            self._build = False

        self._project_root_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
        self._project_build_dir = os.path.join(self._project_root_dir, "build")
        self._sysroot_dir = os.path.join(self._project_build_dir, "sysroot", self._arch)
        self._cerbero_root_dir = self._external_cerbero_build_path or os.path.join(self._project_build_dir, "cerbero")

        cerbero_arch_suffix = self._arch.replace("_", "-")
        self._cerbero_command_args = [
            os.path.join(self._cerbero_root_dir, "cerbero-uninstalled"),
            "-c",
            os.path.join(self._cerbero_root_dir, "config", f"cross-android-{cerbero_arch_suffix}")
        ]

    def _get_package_version(self, package_name):
        output = subprocess.check_output(self._cerbero_command_args + ["packageinfo", package_name], encoding="utf-8")
        m = re.search(r"Version:\s+([0-9.]+)", output)
        if m:
            return m.group(1)
        else:
            raise Exception(f"Cannot find version for package {package_name}")

    def _download_package(self, version, filename):
        url = self._packages_url_template.format(version=version, filename=filename)
        target = os.path.join(self._project_build_dir, filename)
        os.makedirs(self._project_build_dir, exist_ok=True)

        if os.isatty(sys.stdout.fileno()):
            def report(count, block_size, total_size):
                size = total_size / 1024 / 1024
                percent = int(100 * count * block_size / total_size)
                print(f"\r\x1B[J  {url} [{size:.2f} MiB] {percent}% ", flush=True, end="")
        else:
            report = None

        print(f"  {url}... ", flush=True, end="")
        urlretrieve(url, target, report)
        print(f"\r\x1B[J  {url} - done")

    def download_all_packages(self, version):
        print("Downloading packages...")
        self._download_package(version, self._devel_package_name_template.format(arch=self._arch, version=version))
        self._download_package(version, self._runtime_package_name_template.format(arch=self._arch, version=version))

    def copy_all_packages_from_external_cerbero_build(self):
        if not self._external_cerbero_build_path:
            raise Exception("Illegal configuration: Cerbero external build path is not specified")

        print(f"Copying packages from existing Cerbero build at {self._external_cerbero_build_path}...")

        version = self._get_package_version("wpewebkit")

        # Don't do copy if given external cerbero path points to internal cerbero clone.
        # In that case internally built packages are already placed to self._project_build_dir
        if os.path.commonprefix([self._external_cerbero_build_path,
                                 self._project_build_dir]) == self._project_build_dir:
            return version

        filenames = [
            self._devel_package_name_template.format(arch=self._arch, version=version),
            self._runtime_package_name_template.format(arch=self._arch, version=version)
        ]

        os.makedirs(self._project_build_dir, exist_ok=True)
        for filename in filenames:
            src = os.path.join(self._external_cerbero_build_path, filename)
            dest = os.path.join(self._project_build_dir, filename)

            if not os.path.exists(src):
                raise Exception(f"Unable to find package {src}")

            print(f"Copying {src} into {dest}")
            shutil.copyfile(src, dest)

        return version

    def _patch_wpewebkit_recipe_for_debug_build(self):
        assert self._build, "build mode should be activated"
        assert self._debug, "debug mode should be activated"

        recipe_path = os.path.join(self._cerbero_root_dir, "recipes", "wpewebkit.recipe")
        with fileinput.FileInput(recipe_path, inplace=True) as file:
            for line in file:
                line = line.replace("-DLOG_DISABLED=1", "-DLOG_DISABLED=0")
                line = line.replace("-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_BUILD_TYPE=Debug")
                line = line.replace("self.append_env('WEBKIT_DEBUG', '')", "self.append_env('WEBKIT_DEBUG', 'all')")
                print(line, end="")

        packages = ["wpewebkit.package", "wpewebkit-core.package"]
        for package_path in packages:
            package_path = os.path.join(self._cerbero_root_dir, "packages", package_path)
            with fileinput.FileInput(package_path, inplace=True) as file:
                for line in file:
                    line = line.replace("strip = True", "strip = False")
                    print(line, end="")

    def ensure_cerbero(self):
        if not self._build:
            raise Exception("Illegal configuration: build mode is not specified")

        if os.path.isdir(self._cerbero_root_dir):
            print("Updating Cerbero git repository...")
            subprocess.check_call(
                ["git", "reset", "--hard", f"origin/{self._cerbero_branch}"], cwd=self._cerbero_root_dir)
            subprocess.check_call(["git", "pull", "origin", self._cerbero_branch], cwd=self._cerbero_root_dir)
        else:
            print("Cloning Cerbero git repository...")
            os.makedirs(self._project_build_dir, exist_ok=True)
            subprocess.check_call(["git", "clone", "--branch", self._cerbero_branch,
                                  self._cerbero_origin, "cerbero"], cwd=self._project_build_dir)

        subprocess.check_call(self._cerbero_command_args + ["bootstrap"])
        if self._debug:
            self._patch_wpewebkit_recipe_for_debug_build()

    def build_deps(self):
        if not self._build:
            raise Exception("Illegal configuration: build mode is not specified")

        print("Building dependencies with Cerbero...")

        os.makedirs(self._project_build_dir, exist_ok=True)
        subprocess.check_call(self._cerbero_command_args +
                              ["package", "-o", self._project_build_dir, "-f", "wpewebkit"])
        return self._get_package_version("wpewebkit")

    def extract_deps(self, version):
        print("Extracting dependencies packages...")

        shutil.rmtree(self._sysroot_dir, True)
        os.makedirs(self._sysroot_dir)

        devel_file_path = os.path.join(self._project_build_dir,
                                       self._devel_package_name_template.format(arch=self._arch, version=version))
        subprocess.check_call(["tar", "xf", devel_file_path, "-C", self._sysroot_dir, "include", "lib/glib-2.0",
                              "share/gst-android/ndk-build/GStreamer.java", "share/gst-android/ndk-build/androidmedia"])

        runtime_file_path = os.path.join(self._project_build_dir,
                                         self._runtime_package_name_template.format(arch=self._arch, version=version))
        subprocess.check_call(["tar", "xf", runtime_file_path, "-C", self._sysroot_dir, "lib"])

    def _copy_headers(self, target_dir):
        shutil.rmtree(target_dir, True)
        os.makedirs(target_dir)

        for header in self._build_includes:
            shutil.copytree(os.path.join(self._sysroot_dir, "include", header[0]), os.path.join(target_dir, header[1]))

    def _adjust_soname(self, original_soname):
        if original_soname.endswith(".so"):
            return original_soname

        split_name = original_soname.split(".")
        if len(split_name) > 2:
            if split_name[-2] == "so":
                return ".".join(split_name[:-2]) + "_" + split_name[-1] + ".so"
            elif split_name[-3] == "so":
                return ".".join(split_name[:-3]) + "_" + split_name[-2] + "_" + split_name[-1] + ".so"

        raise Exception(f"Invalid soname: {original_soname}")

    def _read_elf(self, lib_path):
        soname_list = []
        needed_list = []

        p = subprocess.Popen(["readelf", "-d", lib_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             env=dict(os.environ, LC_ALL="C"), encoding="utf-8")
        (stdout, _) = p.communicate()

        for line in stdout.splitlines():
            needed = re.match(r"^ 0x[0-9a-f]+ \(NEEDED\)\s+Shared library: \[(.+)\]$", line)
            if needed:
                needed_list.append(needed.group(1))
            soname = re.match(r"^ 0x[0-9a-f]+ \(SONAME\)\s+Library soname: \[(.+)\]$", line)
            if soname:
                soname_list.append(soname.group(1))

        if len(soname_list) == 0:
            soname_list = [os.path.basename(lib_path)]

        assert len(soname_list) == 1, f"we should have only 1 soname in {lib_path} (but we got {len(soname_list)})"
        return (soname_list[0], needed_list)

    def _replace_soname_values(self, lib_path):
        with open(lib_path, "rb") as lib_file:
            content = lib_file.read()

        for pair in self._soname_replacements:
            content = content.replace(bytes(pair[0], encoding="utf8"), bytes(pair[1], encoding="utf8"))

        with open(lib_path, "wb") as lib_file:
            lib_file.write(content)

    def _copy_gst_plugins(self, target_dir):
        shutil.rmtree(target_dir, True)
        shutil.copytree(os.path.join(self._sysroot_dir, "lib", "gstreamer-1.0"), target_dir)
        for plugin_path in Path(target_dir).rglob("*.so"):
            self._replace_soname_values(plugin_path)

    def _copy_gio_modules(self, target_dir):
        shutil.rmtree(target_dir, True)
        os.makedirs(target_dir)
        sysroot_gio_module_file = os.path.join(self._sysroot_dir, "lib", "gio", "modules", "libgiognutls.so")
        shutil.copy(sysroot_gio_module_file, target_dir)
        for plugin_path in Path(target_dir).rglob("*.so"):
            self._replace_soname_values(plugin_path)

    def _copy_gst_android_classes(self, target_dir):
        shutil.rmtree(target_dir, True)
        os.makedirs(target_dir)
        sysroot_android_classes_dir = os.path.join(self._sysroot_dir, "share", "gst-android", "ndk-build")
        shutil.copy(os.path.join(sysroot_android_classes_dir, "GStreamer.java"), target_dir)
        shutil.copytree(os.path.join(sysroot_android_classes_dir, "androidmedia"),
                        os.path.join(target_dir, "androidmedia"))

    def _copy_system_libs(self, target_dir):
        shutil.rmtree(target_dir, True)
        os.makedirs(target_dir)

        sysroot_lib_dir = os.path.join(self._sysroot_dir, "lib")

        libs_paths = list(Path(sysroot_lib_dir).glob("*.so"))
        libs_paths.extend(list(Path(os.path.join(sysroot_lib_dir, "wpe-webkit-1.0", "injected-bundle")).glob("*.so")))

        self._soname_replacements = Bootstrap._soname_replacements.copy()

        for lib_path in libs_paths:
            soname, _ = self._read_elf(lib_path)
            adjusted_soname = self._adjust_soname(soname)
            if adjusted_soname != soname:
                self._soname_replacements.append((soname, adjusted_soname))
            target_file = os.path.join(target_dir, os.path.relpath(
                os.path.dirname(lib_path), sysroot_lib_dir), adjusted_soname)
            os.makedirs(os.path.dirname(target_file), exist_ok=True)
            shutil.copyfile(lib_path, target_file)

        for pair in self._soname_replacements:
            assert len(pair[0]) == len(pair[1]), f"sonames {pair[0]} and {pair[1]} don't have the same length"

        for lib_path in Path(target_dir).rglob("*.so"):
            self._replace_soname_values(lib_path)

        shutil.copytree(os.path.join(sysroot_lib_dir, "glib-2.0"), os.path.join(target_dir, "glib-2.0"))

    def _copy_jni_libs(self, src_dir, target_dir):
        shutil.rmtree(target_dir, True)
        os.makedirs(target_dir)

        libs_paths = Path(src_dir).rglob("*.so")
        for lib_path in libs_paths:
            filename = os.path.basename(lib_path)
            if filename not in self._build_libs:
                shutil.copyfile(lib_path, os.path.join(target_dir, filename), follow_symlinks=False)

    def _resolve_deps(self, system_lib_dir, plugins_dir_list):
        soname_set = set()
        needed_set = set(self._base_needed)

        for lib_path in Path(system_lib_dir).rglob("*.so"):
            soname, needed_list = self._read_elf(lib_path)
            soname_set.update([soname])
            needed_set.update(needed_list)

        for plugins_dir in plugins_dir_list:
            for lib_path in Path(plugins_dir).rglob("*.so"):
                _, needed_list = self._read_elf(lib_path)
                needed_set.update(needed_list)

        print("NEEDED but not provided:")
        needed_diff = needed_set - soname_set
        if len(needed_diff) == 0:
            print("    <none>")
        else:
            for entry in needed_diff:
                print("    ", entry)

        print("Provided but not NEEDED:")
        provided_diff = soname_set - needed_set
        if len(provided_diff) == 0:
            print("    <none>")
        else:
            for entry in provided_diff:
                print("    ", entry)

    def _create_android_wrapper_script(self, target_dir):
        assert self._wrapper, "wrapper mode should be activated"

        os.makedirs(target_dir, exist_ok=True)
        target_script_file = os.path.join(target_dir, "wrap.sh")
        with open(target_script_file, "wt", encoding="utf-8", newline="\n") as file:
            file.write(dedent("""\
        #!/system/bin/sh

        cmd=$1
        shift

        os_version=$(getprop ro.build.version.sdk)

        if [ "$os_version" -eq "27" ]; then
            cmd="$cmd -Xrunjdwp:transport=dt_android_adb,suspend=n,server=y -Xcompiler-option --debuggable $@"
        elif [ "$os_version" -eq "28" ]; then
            cmd="$cmd -XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y -Xcompiler-option --debuggable $@"
        else
            cmd="$cmd -XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y $@"
        fi

        exec $cmd
            """))
        os.chmod(target_script_file, 0o755)

    def install_deps(self):
        print("Installing dependencies into wpe-android project...")

        wpe_src_main_dir = os.path.join(self._project_root_dir, "wpe", "src", "main")
        self._copy_headers(os.path.join(wpe_src_main_dir, "cpp", "imported", "include"))

        if self._arch == "arm64":
            android_abi = "arm64-v8a"
        elif self._arch == "armv7":
            android_abi = "armeabi-v7a"
        elif self._arch == "x86":
            android_abi = "x86"
        elif self._arch == "x86_64":
            android_abi = "x86_64"
        else:
            raise Exception("Architecture not supported")

        wpe_imported_lib_dir = os.path.join(wpe_src_main_dir, "cpp", "imported", "lib", android_abi)
        self._copy_system_libs(wpe_imported_lib_dir)
        self._copy_jni_libs(wpe_imported_lib_dir, os.path.join(wpe_src_main_dir, "jniLibs", android_abi))

        wpe_assets_gst_dir = os.path.join(wpe_src_main_dir, "assets", "gstreamer-1.0", android_abi)
        self._copy_gst_plugins(wpe_assets_gst_dir)

        wpe_assets_gio_dir = os.path.join(wpe_src_main_dir, "assets", "gio", android_abi)
        self._copy_gio_modules(wpe_assets_gio_dir)

        self._copy_gst_android_classes(os.path.join(wpe_src_main_dir, "java", "org", "freedesktop", "gstreamer"))
        self._resolve_deps(wpe_imported_lib_dir, [wpe_assets_gst_dir, wpe_assets_gio_dir])

        if self._wrapper:
            self._create_android_wrapper_script(os.path.join(
                self._project_root_dir, "tools", "minibrowser", "src", "main", "resources", "lib", android_abi))

    def run(self):
        version = self.default_version
        if self._external_cerbero_build_path:
            version = self.copy_all_packages_from_external_cerbero_build()
        elif self._build:
            self.ensure_cerbero()
            version = self.build_deps()
        else:
            self.download_all_packages(version)
        self.extract_deps(version)
        self.install_deps()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="This script fetches or builds the dependencies needed by wpe-android"
    )

    parser.add_argument("-a", "--arch", metavar="architecture", required=False, default=Bootstrap.default_arch,
                        choices=["arm64", "armv7", "x86", "x86_64", "all"], help="The target architecture")
    parser.add_argument("-v", "--version", metavar="version", required=False, default=Bootstrap.default_version,
                        help="Specify the wpewebkit version to use (ignored if using --cerbero or --build, "
                             "in these cases the version is taken from the Cerbero build)")
    parser.add_argument("-c", "--cerbero", metavar="path", required=False,
                        help="Path to an external Cerbero build containing already built packages")
    parser.add_argument("-b", "--build", required=False, action="store_true",
                        help="Build dependencies from sources using Cerbero (ignored if --cerbero is specified)")
    parser.add_argument("-d", "--debug", required=False, action="store_true",
                        help="Build the binaries with debug symbols (ignored if --build is not specified)")
    parser.add_argument("-w", "--wrapper", required=False, action="store_true",
                        help="Create Android wrapper script "
                             "(see: https://developer.android.com/ndk/guides/wrap-script, "
                             "not compatible with Bundle creation)")

    args = parser.parse_args()
    if args.arch == "all":
        for arch in ["arm64", "armv7", "x86", "x86_64"]:
            args.arch = arch
            print(args)
            Bootstrap(args).run()
    else:
        print(args)
        Bootstrap(args).run()
