#!/bin/python

import os
import shutil
import subprocess
import sys

from bootstrap import Bootstrap
from enum import Enum
from os.path import expanduser

class Recipe(Enum):
    WEBKIT = "wpewebkit"
    WPEBACKEND = "wpebackend-android"
    LIBWPE = "libwpe"

    def values():
        return set(item.value for item in Recipe)

class Patch:
    def __init__(self, arch, recipe):
        if recipe not in Recipe.values():
            raise Exception("Unsupported recipe. Supported recipes: ", Recipe.values())
        self.__arch = arch
        self.__root = os.getcwd()
        self.__recipe = recipe
        self.__build_dir = os.path.join(os.getcwd(), 'cerbero')
        self.__original_rc_file = None

    def __get_rc_file_name(self):
        shell = os.environ.get('SHELL', '/bin/bash')
        if 'zsh' in shell:
            return '.zshrc'
        return '.bashrc'

    def __get_install_list(self):
        if self.__recipe == Recipe.WEBKIT.value:
            return ['libWPEWebKit-1.0.so']
        elif self.__recipe == Recipe.WPEBACKEND.value:
            raise Exception("Unimplemented")
        elif self.__recipe == Recipe.LIBWPE.value:
            raise Exception("Unimplemented")
        else:
            raise Exception("Unsupported recipe. Supported recipes: ", Recipe.values())

    def __patch_rc_file(self):
        sources_path = 'build/sources/android_{0}/'.format(self.__arch)
        if self.__recipe == Recipe.WEBKIT.value:
            # TODO figure out version
            ninja_build_dir = sources_path + 'wpewebkit-2.30.4/_builddir'
            command = [
                    'ninja -C ' + ninja_build_dir + '\n',
                    'ninja -C {0} install\n'.format(ninja_build_dir)
            ]
        elif self.__recipe == Recipe.WPEBACKEND.value:
            raise Exception("Unimplemented")
        elif self.__recipe == Recipe.LIBWPE.value:
            raise Exception("Unimplemented")
        else:
            raise Exception("Unsupported recipe. Supported recipes: ", Recipe.values())
        command.append('exit')
        print(command)

        rc_file = self.__get_rc_file_name()
        self.__original_rc_file = os.path.join(expanduser("~"), rc_file + ".backup")
        patched_rc_file = os.path.join(expanduser("~"), rc_file)
        shutil.copy(patched_rc_file, self.__original_rc_file)
        with open(patched_rc_file, "a") as rc_content:
            for command_line in command:
                rc_content.write(command_line)

    def __restore_rc_file(self):
        rc_file = os.path.join(expanduser("~"), self.__get_rc_file_name())
        shutil.copy(self.__original_rc_file, rc_file)

    def __cerbero_shell_build(self):
        if not os.path.isdir(self.__build_dir):
            raise Exception("You need to run the bootstrap script first")
        os.chdir(self.__build_dir)
        self.__patch_rc_file()
        subprocess.call([
            './cerbero-uninstalled',
            '-c',
            '%s/config/cross-android-%s' %(self.__build_dir, self.__arch),
            'shell'
        ])
        self.__restore_rc_file()
        os.chdir(self.__root)

    def run(self):
        self.__cerbero_shell_build()
        sysroot = os.path.join(self.__build_dir, 'build', 'dist', 'android_' + self.__arch)
        Bootstrap(self.__arch, False).install_deps(sysroot, self.__get_install_list())

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage `./patch.py <arch> <recipe>` (i.e. ./patch.py arm64 wpewebkit)")
        print("Supported recipes: wpewebkit")
        exit()
    Patch(sys.argv[1], sys.argv[2]).run()
