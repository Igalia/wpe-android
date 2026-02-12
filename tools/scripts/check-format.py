#!/usr/bin/env python3

##
# Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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
This script checks all files names and code format for Python, Java, C/C++ and Cmake files.
If called with --hook option, it will only check differencies with previous git commit.
"""

import argparse
import shutil
import sys
import subprocess
import os
import re
import site


def _clang_tool_names(name, version_range):
    # Versioned first, prefer newer versions; then unversioned.
    for version in reversed(range(*version_range)):
        yield f"{name}-{version}"
    yield name


def _find_clang_tool(name, version_range=(13, 22)):
    # A few Linux distributions stash a few tools under
    # /usr/share/clang, so try /usr/local/share/clang as well.
    extended_path = os.environ.get("PATH", "")
    if extended_path:
        extended_path += ":"
    extended_path += "/usr/share/clang:/usr/local/share/clang"

    for candidate in _clang_tool_names(name, version_range):
        location = shutil.which(candidate, path=extended_path)
        if location:
            return location

    raise None


class CheckFormat:
    _project_root_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
    _git = shutil.which("git")
    _clang_format = _find_clang_tool("clang-format")
    _git_clang_format = _find_clang_tool("git-clang-format")
    _editor_config_checker = shutil.which("ec") or os.path.join(site.getuserbase(), "bin", "ec")

    _kebab_case_pattern = re.compile(r"[a-z][a-z0-9.-]+\.[a-z]+")
    _pascal_case_pattern = re.compile(r"[A-Z][a-zA-Z0-9]+\.[a-z]+")
    _snake_case_pattern = re.compile(r"[a-z][a-z0-9_]+\.[a-z]+")

    def __init__(self, hook, diff_since):
        # Using --hook implies --diff, because git-clang-format
        # can run over the diff in that case as well
        self._hook = hook
        self._diff_since = diff_since
        self._diff_files = set() if hook or diff_since else None

    def _check_kebab_case_name(self, file):
        if self._kebab_case_pattern.fullmatch(os.path.basename(file)):
            return True
        else:
            print(f"-- {file} has a wrong file name.\n"
                  "-- Please rename the file using kebab-case format.", file=sys.stderr)
            return False

    def _check_pascal_case_name(self, file):
        if self._pascal_case_pattern.fullmatch(os.path.basename(file)):
            return True
        else:
            print(f"-- {file} has a wrong file name.\n"
                  "-- Please rename the file using PascalCase format.", file=sys.stderr)
            return False

    def _check_snake_case_name(self, file):
        if self._snake_case_pattern.fullmatch(os.path.basename(file)):
            return True
        else:
            print(f"-- {file} has a wrong file name.\n"
                  "-- Please rename the file using snake_case format.", file=sys.stderr)
            return False

    def _get_original_file_content(self, file):
        command = subprocess.run([self._git, "show", f":{file}"],
                                 stdout=subprocess.PIPE,
                                 encoding="utf-8",
                                 check=True,
                                 cwd=self._project_root_dir)
        return command.stdout

    def _check_pep8(self, file):
        if os.path.islink(os.path.join(self._project_root_dir, file)):
            return True

        original_file_content = self._get_original_file_content(file)
        command = subprocess.run([sys.executable, "-m", "pycodestyle", "-"],
                                 input=original_file_content,
                                 stdout=subprocess.PIPE,
                                 encoding="utf-8",
                                 check=False,
                                 cwd=os.path.join(self._project_root_dir, "tools", "scripts"))
        if command.returncode == 0:
            return True
        else:
            print(f"-- {file} is not correctly formatted.\n"
                  f"-- Please call `autopep8 -i {os.path.join(self._project_root_dir, file)}` or fix issues manually.",
                  file=sys.stderr)
            for line in command.stdout.splitlines():
                # Let"s remove "stdin:" prefix from each line
                print(f"-- {line[6:]}", file=sys.stderr)
            return False

    def _check_git_clang_format(self):
        command = [self._git_clang_format, "--diff"]
        if self._hook:
            assert not self._diff_since
        elif self._diff_since:
            command.append(self._diff_since)
        command.append("--")
        command.extend(self._diff_files)
        result = subprocess.run(command,
                                stdout=subprocess.PIPE,
                                encoding="utf-8",
                                cwd=self._project_root_dir)
        if result.returncode != 0:
            print("-- Changes are not correctly formatted.",
                  f"-- Try `{self._clang_format} -style=file -i <path>` to apply fixes",
                  result.stdout, sep="\n", end="", file=sys.stderr)
            return False
        return True

    def _check_clang_format(self, file):
        if os.path.islink(os.path.join(self._project_root_dir, file)):
            return True

        base_name = os.path.basename(file)
        if base_name.endswith(".aidl"):
            base_name = base_name[:-4] + "java"
        elif isinstance(self._diff_files, set):
            self._diff_files.add(file)
            return True

        original_file_content = self._get_original_file_content(file)
        command = subprocess.run([self._clang_format, "--Werror", f"--assume-filename={base_name}", "-style=file"],
                                 input=original_file_content,
                                 stdout=subprocess.PIPE,
                                 encoding="utf-8",
                                 check=True,
                                 cwd=self._project_root_dir)
        formatted_content = command.stdout

        if formatted_content == original_file_content:
            return True
        else:
            print(f"-- {file} is not correctly formatted.\n"
                  f"-- Please call `{self._clang_format} -style=file -i {os.path.join(self._project_root_dir, file)}`.",
                  file=sys.stderr)
            return False

    def _check_cmake_format(self, file):
        if os.path.islink(os.path.join(self._project_root_dir, file)):
            return True

        original_file_content = self._get_original_file_content(file)
        command = subprocess.run([sys.executable, "-m", "cmakelang.format", "-"],
                                 input=original_file_content,
                                 stdout=subprocess.PIPE,
                                 encoding="utf-8",
                                 check=True,
                                 cwd=self._project_root_dir)
        formatted_content = command.stdout

        if formatted_content == original_file_content:
            return True
        else:
            print(f"-- {file} is not correctly formatted.\n"
                  f"-- Please call `cmake-format -i {os.path.join(self._project_root_dir, file)}`.", file=sys.stderr)
            return False

    def _check_file_format(self, file):
        base_name = os.path.basename(file)
        if base_name in ["AndroidManifest.xml", "codeStyleConfig.xml", "Project.xml",
                         "README.md", "LICENSE.md", ".clang-format", ".clang-tidy",
                         ".cmake-format.json", ".editorconfig", ".gitattributes",
                         ".gitignore", "gradlew", "gradlew.bat", "setup.cfg",
                         "WPEServices.java.template", "__init__.py",
                         "CHANGELOG.md"]:
            return True

        top_level_path = file
        # Remove possible multiple '../' parts from start of top_level_path
        while top_level_path.startswith('..' + os.path.sep):
            top_level_path = top_level_path[len('..' + os.path.sep):]

        if top_level_path.startswith("tools/scripts/libraries"):
            return True

        if top_level_path.startswith("tools/scripts/webkitpy"):
            return True

        top_level_folder = top_level_path.split(os.path.sep)[0]

        # Exclude imported folders
        if top_level_folder in ["layouttests", "webdrivertests"]:
            return True

        file_ext = os.path.splitext(file)[1]
        if file_ext == ".py":
            return (self._check_kebab_case_name(file) and self._check_pep8(file))
        if file_ext in [".h", ".cpp", ".java", ".aidl"]:
            return (self._check_pascal_case_name(file) and self._check_clang_format(file))
        if file_ext == ".kt":
            return self._check_pascal_case_name(file)
        if file_ext in [".conf", ".xml", ".png", ".md", ".webp", ".jpg"]:
            return self._check_snake_case_name(file)
        if file_ext in [".pro", ".gradle", ".properties", ".jar", ".yml", ".json", ".html", ".js", ".awk", ".sh"]:
            return self._check_kebab_case_name(file)
        if base_name == "CMakeLists.txt":
            return self._check_cmake_format(file)
        if os.path.isdir(file) and os.path.islink(file):
            return True
        if file_ext in [".in", ".toml", ""]:
            return True

        print(f"-- Unknown file extension for {file}.", file=sys.stderr)
        return False

    def _list_versioned_files(self):
        command = [self._git]
        if self._hook:
            command += ["diff-index", "--cached", "--diff-filter=ACMR", "--name-only", "HEAD"]
        else:
            command += ["ls-files", "-c"]
        result = subprocess.run(command,
                                stdout=subprocess.PIPE,
                                encoding="utf-8",
                                check=True,
                                cwd=self._project_root_dir)
        return result.stdout.splitlines()

    def _run_editor_config_checker(self):
        excludes = (
            "\\.git/|\\.gradle/|\\.idea/|build/|gradlew.bat|layouttests/|webdrivertests/|tools/scripts/libraries/"
        )
        command = subprocess.run([self._editor_config_checker, "-no-color", "-disable-indentation",
                                  "-exclude", excludes],
                                 stdout=subprocess.PIPE,
                                 encoding="utf-8",
                                 check=False,
                                 cwd=self._project_root_dir)
        if command.returncode == 0:
            return True
        else:
            print(f"-- Some files in {self._project_root_dir} are not correctly formatted.\n"
                  "-- Please use an EditorConfig compatible IDE or fix issues manually.", file=sys.stderr)
            for line in command.stdout.splitlines():
                print(f"-- {line}", file=sys.stderr)
            return False

    def run(self):
        try:
            if not self._run_editor_config_checker():
                return 2

            files = self._list_versioned_files()
            for file in files:
                if not self._check_file_format(file):
                    return 2
            if self._diff_files:
                if not self._check_git_clang_format():
                    return 2
        except Exception as err:
            print(f"System error: {err}", file=sys.stderr)
            return 3
        return 0


def main():
    parser = argparse.ArgumentParser()
    parser_group = parser.add_mutually_exclusive_group()
    parser_group.add_argument("--hook", action="store_true", help="Run as a Git hook")
    parser_group.add_argument("--diff-format", type=str, metavar="COMMITTISH", default=None,
                              help="Check format of lines changed by diff in standard input")
    options = parser.parse_args()

    checker = CheckFormat(options.hook, options.diff_format)
    sys.exit(checker.run())


if __name__ == "__main__":
    main()
