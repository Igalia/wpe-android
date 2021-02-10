#!/bin/python

import os
import subprocess
import sys

root = os.getcwd()
build_dir = os.path.join(os.getcwd(), 'build')

def cerbero_command(arch, args):
    os.chdir(build_dir)
    command = [
        './cerbero-uninstalled', '-c',
        '%s/config/cross-android-%s' %(build_dir, arch)
    ]
    command += args
    subprocess.call(command)

def ensure_cerbero(arch):
    origin = 'ssh://git@gitlab.igalia.com:4429/fjimenez/cerbero.git'
    branch = 'wpe-android'

    if os.path.isdir(build_dir):
        os.chdir(build_dir)
        subprocess.call(['git', 'reset', '--hard', 'origin/' + branch])
        subprocess.call(['git', 'pull', 'origin', branch])
        os.chdir(root)
    else:
        subprocess.call(['git', 'clone', '--branch', branch, origin, 'build'])

    cerbero_command(arch, ['bootstrap'])

def build_deps(arch):
    cerbero_command(arch, ['package', '-f', 'wpewebkit'])

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: ./bootstrap.py <arch> (i.e. ./bootstrap.py arm64)")
        exit()
    arch = sys.argv[1]
    ensure_cerbero(arch)
    build_deps(arch)

