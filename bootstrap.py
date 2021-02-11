#!/bin/python

import glob
import os
import shutil
import subprocess
import sys

class Bootstrap:
    def __init__(self, arch):
        self.__arch = arch
        self.__root = os.getcwd()
        self.__build_dir = os.path.join(os.getcwd(), 'build')

    def __cerbero_command(self, args):
        os.chdir(self.__build_dir)
        command = [
            './cerbero-uninstalled', '-c',
            '%s/config/cross-android-%s' %(self.__build_dir, self.__arch)
        ]
        command += args
        subprocess.call(command)

    def __ensure_cerbero(self):
        origin = 'ssh://git@gitlab.igalia.com:4429/fjimenez/cerbero.git'
        branch = 'wpe-android'

        if os.path.isdir(self.__build_dir):
            os.chdir(self.__build_dir)
            subprocess.call(['git', 'reset', '--hard', 'origin/' + branch])
            subprocess.call(['git', 'pull', 'origin', branch])
            os.chdir(self.__root)
        else:
            subprocess.call(['git', 'clone', '--branch', branch, origin, 'build'])

        self.__cerbero_command(['bootstrap'])

    def __extract_deps(self):
        os.chdir(self.__build_dir)
        sysroot = os.path.join(self.__build_dir, 'sysroot')
        if os.path.isdir(sysroot):
            shutil.rmtree(sysroot)
        os.mkdir(sysroot)
        for f in glob.glob('wpewebkit-android-%s-*.tar.xz' %self.__arch):
            file_path = os.path.join(self.__build_dir, f)
            subprocess.call(['tar', 'xvf', file_path, '-C', sysroot])
            os.remove(file_path)
        # TODO: move deps to corresponding location. i.e libs to jniLibs folder.

    def __build_deps(self):
        self.__cerbero_command(['package', '-f', 'wpewebkit'])

    def run(self):
        self.__ensure_cerbero()
        for f in glob.glob('wpewebkit-android-*.tar.xz'):
            os.remove(f)
        self.__build_deps()
        self.__extract_deps()

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: ./bootstrap.py <arch> (i.e. ./bootstrap.py arm64)")
        exit()
    Bootstrap(sys.argv[1]).run()

