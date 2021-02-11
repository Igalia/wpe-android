#!/bin/python

import glob
import os
import shutil
import subprocess
import sys

from pathlib import Path

class Bootstrap:
    def __init__(self, arch):
        self.__arch = arch
        self.__root = os.getcwd()
        self.__build_dir = os.path.join(os.getcwd(), 'build')
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
            'glib-2.0',
            'wpe-1.0',
            'wpe-android',
            'wpe-webkit-1.0'
        ]

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

    def __build_deps(self):
        self.__cerbero_command(['package', '-f', 'wpewebkit'])

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

    def __install_deps(self):
        sysroot = os.path.join(self.__build_dir, 'sysroot')
        wpe = os.path.join(self.__root, 'wpe')

        include_dir = os.path.join(wpe, 'imported', 'include')
        if os.path.exists(include_dir):
            shutil.rmtree(include_dir)
        os.makedirs(include_dir)
        for header in self.__build_includes:
            shutil.copytree(os.path.join(sysroot, 'include', header),
                            os.path.join(include_dir, header))

        if self.__arch == 'arm64':
            android_abi = 'arm64-v8a'
        elif self.__arch == 'arm':
            android_abi = 'armeabi-v7a'
        else:
            raise Exception('Architecture not supported')

        sysroot_lib = os.path.join(sysroot, 'lib')
        lib_dir = os.path.join(wpe, 'imported', 'lib', android_abi)
        if os.path.exists(lib_dir):
            shutil.rmtree(lib_dir)
        os.makedirs(lib_dir)
        for lib in self.__build_libs:
            origin = os.path.join(sysroot_lib, lib)
            dst = os.path.join(lib_dir, lib)
            if os.path.isdir(origin):
                shutil.copytree(origin, dst)
            else:
                shutil.copy(origin, dst, follow_symlinks=False)

        os.symlink(os.path.join(lib_dir, 'libWPEBackend-android.so'),
                os.path.join(lib_dir, 'libWPEBackend-default.so'))

        jnilib_dir = os.path.join(wpe, 'src', 'main', 'jniLibs', android_abi)
        if os.path.exists(jnilib_dir):
            shutil.rmtree(jnilib_dir)
        os.makedirs(jnilib_dir)
        for lib_path in Path(sysroot_lib).glob('*.so'):
            lib_name = os.path.basename(lib_path)
            if lib_name in self.__build_libs:
                continue
            shutil.copy(lib_path, os.path.join(jnilib_dir, lib_name))

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

