#!/usr/bin/env python3

"""
This script checks all files names and code format for Python, Java, C/C++ and Cmake files.
If called with --hook option, it will only check differencies with previous git commit.
"""

import shutil
import sys
import subprocess
import os
import re
import site


class CheckFormat:
    _project_root_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
    _git = shutil.which("git")
    _clang_format = shutil.which("clang-format-14") or \
        shutil.which("clang-format-13") or \
        shutil.which("clang-format-12") or \
        shutil.which("clang-format")
    _editor_config_checker = os.path.join(site.getuserbase(), "bin", "ec")

    _kebab_case_pattern = re.compile(r"[a-z][a-z0-9.-]+\.[a-z]+")
    _pascal_case_pattern = re.compile(r"[A-Z][a-zA-Z0-9]+\.[a-z]+")
    _snake_case_pattern = re.compile(r"[a-z][a-z0-9_]+\.[a-z]+")

    def __init__(self, args):
        self._hook = (args and args[0] == "--hook")

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

    def _check_clang_format(self, file):
        base_name = os.path.basename(file)
        if base_name.endswith(".aidl"):
            base_name = base_name[:-4] + "java"

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
                         "README.md", ".clang-format", ".clang-tidy", ".cmake-format.json",
                         ".editorconfig", ".gitattributes", ".gitignore", "gradlew",
                         "gradlew.bat", "setup.cfg", "WPEServices.java.template"]:
            return True

        file_ext = os.path.splitext(file)[1]
        if file_ext == ".py":
            return (self._check_kebab_case_name(file) and self._check_pep8(file))
        if file_ext in [".h", ".cpp", ".java", ".aidl"]:
            return (self._check_pascal_case_name(file) and self._check_clang_format(file))
        if file_ext == ".kt":
            return self._check_pascal_case_name(file)
        if file_ext in [".conf", ".xml", ".png", ".md"]:
            return self._check_snake_case_name(file)
        if file_ext in [".pro", ".gradle", ".properties", ".jar", ".yml"]:
            return self._check_kebab_case_name(file)
        if base_name == "CMakeLists.txt":
            return self._check_cmake_format(file)

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
        command = subprocess.run([self._editor_config_checker, "-no-color", "-disable-indentation",
                                  "-exclude", "\\.git/|\\.gradle/|\\.idea/|build/|gradlew.bat"],
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
        except Exception as err:
            print(f"System error: {err}", file=sys.stderr)
            return 3
        return 0


if __name__ == "__main__":
    checker = CheckFormat(sys.argv[1:])
    sys.exit(checker.run())
