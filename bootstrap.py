#!/bin/python

"""
This script takes care of fetching, building and installing all WPE Android dependencies,
including libwpe, WPEBackend-android and WPEWebKit.

The cross-compilation work is done by Cerbero: https://gitlab.igalia.com/ferjm/cerbero

After cloning Cerbero's source through git in the `build` folder, the process starts with
the following Cerbero command:

`./cerbero-uninstalled -c config/cross-android-<android_abi> -f wpewebkit`

where `<android_abi>` varies depending on the given architecture target.

The logic for this command is in the WPEWebKit packaging recipe in Cerbero's repo:
https://gitlab.igalia.com/ferjm/cerbero/-/blob/b9c3b76efb1ed7e2fedfcd6838e638a194df2da8/packages/wpewebkit.package

This command triggers the build for all WPEWebKit dependencies. After that WPEWebKit itself
is built. You can find the recipes for all dependencies and WPEWebKit build in the
`recipes` folder of Cerbero's repo.

Once WPEWebKit and all dependencies are built, the pre-package step is executed.

The pre-packaging step tries to work around a limitation of Android's package manager.
Android package manager only unpacks libxxx.so named libraries so any library with
versioning (i.e. libxxx.so.1) will be ignored. To fix this we rename all versioned
libraries to the libxxx.so form. For example, a library named libfoo.so.1 will become
libfoo_1.so. Apart from renaming the actual library files, we need to tweak the
SONAME and NEEDED values as well to reflect the name changes.

After pre-package, the actual packaging starts. This takes care of packaging the result of
cross-compiling all the dependencies. The list of assets that are packaged is defined by
the `files` variable in the packaging recipe. The syntax `wpeandroid:libs:stl` means
'from the recipe wpeandroid, include the libraries (`files_libs` in the recipe) and the
STL lib (`files_stl` in the recipe). You can think of the `:` separating the file types
as commas in a list. For most recipes we only care about the libraries, except for
WPEWebKit from which we want everything.

Two different tar files are generated during this process. One containing the runtime
assets and another one with the development assets.

After packaging, the post-package step is executed. During this step we need to undo the
changes done in the pre-packaging step. This is restoring the versioned libraries references
in Cerbero's sysroot. After that we need to process the generated runtime package to
rename the versioned libraries from the libxxx.so.1 form to the libxxx_1.so one to match
the changes done during the pre-packaging step. We also need to take care of the symbolic
links to reflect the naming changes.

After that the packaging work is complete and we are done with Cerbero.

The next step is extracting the packages in the `build/sysroot` folder and copying its content
in the appropriate folders. This is done by the `__install_deps` function.

"""

import glob
import os
import re
import shutil
import subprocess
import sys

from pathlib import Path

class Bootstrap:
    def __init__(self, arch):
        self.__version = '2.30.4'
        self.__arch = arch
        self.__root = os.getcwd()
        self.__build_dir = os.path.join(os.getcwd(), 'build')
        # These are the libraries that the glue code link with,
        # that go into the `imported` folder and cannot go into
        # the `jniFolder` to avoid a duplicated library issue.
        self.__build_libs = [
            'glib-2.0',
            'libglib-2.0.so',
            'libwpe-1.0.so',
            'libWPEBackend-android.so',
            'libWPEWebKit-1.0.so',
            'libWPEWebKit-1.0_3.so',
            'libWPEWebKit-1.0_3.11.7.so'
        ]
        self.__build_includes = [
            ['glib-2.0', 'glib-2.0'],
            ['libsoup-2.4', 'libsoup-2.4'],
            ['wpe-1.0', 'wpe'],
            ['wpe-android', 'wpe-android'],
            ['wpe-webkit-1.0', 'wpe-webkit'],
            ['xkbcommon', 'xkbcommon']
        ]
        self.__soname_replacemenets = [
            ('libnettle.so.6', 'libnettle_6.so'), # This entry is not retrievable from the packaged libnettle.so
        ]
        self.__base_needed = set(['libWPEWebKit-1.0_3.so'])

    def __cerbero_command(self, args):
        os.chdir(self.__build_dir)
        command = [
            './cerbero-uninstalled', '-c',
            '%s/config/cross-android-%s' %(self.__build_dir, self.__arch)
        ]
        command += args
        subprocess.call(command)

    def __ensure_cerbero(self):
        # TODO: change this to a public URL once we publish the
        #       cerbero changes
        origin = 'ssh://git@gitlab.igalia.com:4429/ferjm/cerbero.git'
        branch = 'wpe-android'

        if os.path.isdir(self.__build_dir):
            os.chdir(self.__build_dir)
            subprocess.call(['git', 'reset', '--hard', 'origin/' + branch])
            subprocess.call(['git', 'pull', 'origin', branch])
            os.chdir(self.__root)
        else:
            subprocess.call(['git', 'clone', '--branch', branch, origin, 'build'])

        self.__cerbero_command(['bootstrap'])

    def __build_deps(self):
        self.__cerbero_command(['package', '-f', 'wpewebkit'])

    def __extract_deps(self):
        os.chdir(self.__build_dir)
        sysroot = os.path.join(self.__build_dir, 'sysroot')
        if os.path.isdir(sysroot):
            shutil.rmtree(sysroot)
        os.mkdir(sysroot)

        devel_file_path = os.path.join(self.__build_dir, 'wpewebkit-android-%s-%s.tar.xz' %(self.__arch, self.__version))
        subprocess.call(['tar', 'xf', devel_file_path, '-C', sysroot, 'include', 'lib/glib-2.0'])

        runtime_file_path = os.path.join(self.__build_dir, 'wpewebkit-android-%s-%s-runtime.tar.xz' %(self.__arch, self.__version))
        subprocess.call(['tar', 'xf', runtime_file_path, '-C', sysroot, 'lib'])

    def __copy_headers(self, sysroot_dir, include_dir):
        if os.path.exists(include_dir):
            shutil.rmtree(include_dir)
        os.makedirs(include_dir)

        for header in self.__build_includes:
            shutil.copytree(os.path.join(sysroot_dir, 'include', header[0]),
                            os.path.join(include_dir, header[1]))

    def __adjust_soname(self, initial):
        if initial.endswith('.so'):
            return initial

        split = initial.split('.')
        assert len(split) > 2
        assert split[-2] == 'so'
        return '.'.join(split[:-2]) + '_' + split[-1] + '.so'

    def __read_elf(self, lib_path):
        soname_list = []
        needed_list = []

        p = subprocess.Popen(["readelf", "-d", lib_path], stdout=subprocess.PIPE)
        (stdout, stderr) = p.communicate()

        for line in stdout.decode().split('\n'):
            needed = re.match("^ 0x[0-9a-f]+ \(NEEDED\)\s+Shared library: \[(.+)\]$", line)
            if needed:
                needed_list.append(needed.group(1))
            soname = re.match("^ 0x[0-9a-f]+ \(SONAME\)\s+Library soname: \[(.+)\]$", line)
            if soname:
                soname_list.append(soname.group(1))

        assert len(soname_list) == 1
        return soname_list[0], needed_list

    def __replace_soname_values(self, lib_path):
        with open(lib_path, 'rb') as lib_file:
            contents = lib_file.read()

        for pair in self.__soname_replacemenets:
            contents = contents.replace(bytes(pair[0], encoding='utf8'), bytes(pair[1], encoding='utf8'))

        with open(lib_path, 'wb') as lib_file:
            lib_file.write(contents)

    def __copy_libs(self, sysroot_dir, lib_dir):
        if os.path.exists(lib_dir):
            shutil.rmtree(lib_dir)
        os.makedirs(lib_dir)

        sysroot_libs = os.path.join(sysroot_dir, 'lib')
        sysroot_gio_modules = os.path.join(sysroot_libs, 'gio', 'modules')
        lib_paths = list(map(lambda x: str(x), Path(sysroot_libs).glob('*.so')))
        lib_paths += list(map(lambda x: str(x), Path(sysroot_gio_modules).glob('*.so')))

        for lib_path in lib_paths:
            soname, _ = self.__read_elf(lib_path)

            adjusted_soname = self.__adjust_soname(soname)
            if (adjusted_soname != soname):
                self.__soname_replacemenets.append((soname, adjusted_soname))
            shutil.copy(lib_path, os.path.join(lib_dir, adjusted_soname))

        for pair in self.__soname_replacemenets:
            assert len(pair[0]) == len(pair[1])

        for lib_path in Path(lib_dir).glob('*.so'):
            self.__replace_soname_values(lib_path)

    def __copy_jni_libs(self, lib_dir, jni_lib_dir):
        if os.path.exists(jni_lib_dir):
            shutil.rmtree(jni_lib_dir)
        os.makedirs(jni_lib_dir)

        for lib_path in Path(lib_dir).glob('*.so'):
            if os.path.basename(lib_path) in self.__build_libs:
                continue
            shutil.copy(lib_path, os.path.join(jni_lib_dir, os.path.basename(lib_path)))

    def __resolve_deps(self, lib_dir):
        soname_set = set()
        needed_set = self.__base_needed

        for lib_path in Path(lib_dir).glob('*.so'):
            soname, needed_list = self.__read_elf(lib_path)
            soname_set.update([soname])
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

    def __install_deps(self):
        sysroot = os.path.join(self.__build_dir, 'sysroot')
        wpe = os.path.join(self.__root, 'wpe')

        self.__copy_headers(sysroot, os.path.join(wpe, 'imported', 'include'))

        if self.__arch == 'arm64':
            android_abi = 'arm64-v8a'
        elif self.__arch == 'arm':
            android_abi = 'armeabi-v7a'
        else:
            raise Exception('Architecture not supported')

        sysroot_lib = os.path.join(sysroot, 'lib')
        lib_dir = os.path.join(wpe, 'imported', 'lib', android_abi)
        self.__copy_libs(sysroot, lib_dir)
        self.__resolve_deps(lib_dir)

        jnilib_dir = os.path.join(wpe, 'src', 'main', 'jniLibs', android_abi)
        self.__copy_jni_libs(lib_dir, jnilib_dir)

        gio_dir = os.path.join(jnilib_dir, 'gio', 'modules')
        if os.path.exists(gio_dir):
            shutil.rmtree(gio_dir)
        os.makedirs(gio_dir)
        shutil.copy(os.path.join(sysroot_lib, 'gio', 'modules', 'libgiognutls.so'),
                    os.path.join(gio_dir, 'libgiognutls.so'))

        os.symlink(os.path.join(lib_dir, 'libWPEBackend-android.so'),
                os.path.join(lib_dir, 'libWPEBackend-default.so'))

        shutil.copytree(os.path.join(sysroot_lib, 'glib-2.0'),
                        os.path.join(lib_dir, 'glib-2.0'))

    def run(self):
        self.__ensure_cerbero()
        self.__build_deps()
        self.__extract_deps()
        self.__install_deps()

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: ./bootstrap.py <arch> (i.e. ./bootstrap.py arm64)")
        exit()
    Bootstrap(sys.argv[1]).run()

