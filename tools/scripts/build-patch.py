#!/usr/bin/env python3

"""
This script builds a modified version of a recipe built with Cerbero
and copies the built dependencies to the wpe-android project.

For example, to patch WPEWebkit and test the modifications in wpe-android,
you need to first modify the source code in
[project root]/build/cerbero/build/sources/android_<arch>/wpewebkit-<version>
folder and then call this script as:

build-patch.py --arch <arch>

If --arch argument is not specified, default architecture is 'arm64'
(the same default architecture as in the bootstrap script).

You can build any different recipe by specifying --recipe <recipe name> when
calling the script.
"""

import os
import subprocess
import argparse
import shutil
import tempfile
from bootstrap import Bootstrap


class BuildPatch:
    default_recipe = "wpewebkit"

    def __init__(self, args=None):
        args = args or {}
        if not isinstance(args, dict):
            args = vars(args)

        self._arch = args["arch"] if "arch" in args else Bootstrap.default_arch
        self._recipe = args["recipe"] if "recipe" in args else self.default_recipe

        self._project_root_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
        self._project_build_dir = os.path.join(self._project_root_dir, "build")
        self._sysroot_dir = os.path.join(self._project_build_dir, "sysroot", self._arch)
        self._cerbero_root_dir = os.path.join(self._project_build_dir, "cerbero")
        self._cerbero_dist_dir = os.path.join(self._cerbero_root_dir, "build", "dist", f"android_{self._arch}")

        cerbero_arch_suffix = self._arch.replace("_", "-")
        self._cerbero_command_args = [
            os.path.join(self._cerbero_root_dir, "cerbero-uninstalled"),
            "-c",
            os.path.join(self._cerbero_root_dir, "config", f"cross-android-{cerbero_arch_suffix}")
        ]

    def build_recipe(self):
        print(f"Building recipe {self._recipe}...")
        with tempfile.NamedTemporaryFile() as ref_temp_file:
            subprocess.check_call(self._cerbero_command_args +
                                  ["buildone", self._recipe, "--steps", "configure",
                                   "compile", "install", "post_install"])
            output = subprocess.check_output(rf'find . -type f -newer {ref_temp_file.name} -printf "%P\n"',
                                             shell=True, cwd=self._cerbero_dist_dir, encoding="utf-8")

        include_prefix = "include" + os.path.sep
        lib_prefix = "lib" + os.path.sep
        changed_files = [filename for filename in output.splitlines() if filename.startswith(include_prefix)
                         or filename.startswith(lib_prefix)]
        return changed_files

    def copy_built_files_to_sysroot(self, built_files):
        print("Copying built files to sysroot...")
        for file in built_files:
            shutil.copyfile(os.path.join(self._cerbero_dist_dir, file), os.path.join(self._sysroot_dir, file))

    def run(self):
        self.copy_built_files_to_sysroot(self.build_recipe())
        Bootstrap({"arch": self._arch}).install_deps()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="This script builds a patched version of a Cerbero recipe "
                    "and copies the built dependencies to the wpe-android project"
    )

    parser.add_argument("-a", "--arch", metavar="architecture", required=False, default=Bootstrap.default_arch,
                        choices=["arm64", "armv7", "x86", "x86_64"], help="The target architecture")
    parser.add_argument("-r", "--recipe", metavar="recipe", required=False, default=BuildPatch.default_recipe,
                        help="Specify the Cerbero recipe to build")

    args = parser.parse_args()
    print(args)
    BuildPatch(args).run()
