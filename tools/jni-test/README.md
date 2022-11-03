# JNI Testing

In order to communicate between Android and native code, wpe-android uses
[JNI](https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/jniTOC.html).

To simplify calls and help managing references, a small wrapping API has been developped in
[Common/JNI](./wpe/src/main/cpp/Common/JNI).

This subproject runs a series of tests to verify that the wrapping code is behaving correctly. It also shows
examples of how to use the wrapping API.

Tests are run using a classical Java VM (needs JDK >= 11) and don't need any specific Android device or emulator.

## How to build and run JNI tests

You need:
- cmake >= 3.24
- a C++ 17 compiler (tested with GCC 9.4.0, GCC 11.2.0 and Clang 14.0.0)
- openjdk >= 11
- clang-tidy-14

To build the tests, call:
```
$ cmake -S. -Bbuild
$ cmake --build build
```

To run the tests, call:
```
$ cmake --build build --target run
```

You can also directly call the Java tests executor:
```
$ cd build
$ java -Djava.library.path=. -jar JavaJNITest.jar
```

## Debugging the JNI wrapping API

Easiest way of debugging the JNI wrapping API is to use [Visual Code](https://code.visualstudio.com/) IDE with the
[CMake Tools](https://marketplace.visualstudio.com/items?itemName=ms-vscode.cmake-tools),
[clangd](https://marketplace.visualstudio.com/items?itemName=llvm-vs-code-extensions.vscode-clangd) and
[Debugger for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-debug) plugins.

It will allow to automatically integrate and build the cmake project, activate syntax highlighting, code navigation and
warnings.

To debug the native code and the Java code at the same time, you will need to run the Java interpretor within gdb and
then remotely connect to the Java debugger.

For that you can use the following launch configuration in `.vscode/launch.json` file:
```
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "01 - Launch Native Debugger",
      "type": "cppdbg",
      "request": "launch",
      "program": "/usr/bin/java",
      "args": [
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:35761",
        "-Djava.library.path=${workspaceFolder}/build",
        "-jar",
        "${workspaceFolder}/build/JavaJNITest.jar"
      ],
      "stopAtEntry": false,
      "cwd": "${workspaceFolder}/build",
      "environment": [],
      "externalConsole": false,
      "MIMode": "gdb",
      "setupCommands": [
        {
          "text": "-enable-pretty-printing",
          "ignoreFailures": true
        },
        {
          "text": "-gdb-set disassembly-flavor intel",
          "ignoreFailures": true
        },
        {
          "text": "handle SIGSEGV nostop noprint pass"
        }
      ],
      "preLaunchTask": "Build"
    },
    {
      "type": "java",
      "name": "02 - Attach Java Debugger",
      "request": "attach",
      "hostName": "localhost",
      "port": "35761",
      "sourcePaths": [
        "${workspaceFolder}/java"
      ]
    }
  ]
}
```

And add the build task into `.vscode/tasks.json` file:
```
{
  "version": "2.0.0",
  "tasks": [
    {
      "type": "cmake",
      "label": "Build",
      "command": "build",
      "targets": [
        "jniTest",
        "JavaJNITest"
      ],
      "group": "build",
      "problemMatcher": [],
      "detail": "CMake build task"
    }
  ]
}
```

To launch the debugging session you must first execute the `01 - Launch Native Debugger` command and, when the Java
interpretor has started, the `02 - Attach Java Debugger` command.

Obvisouly, the same process can be executed in command line, calling gdb manually. In this case take special care to
add this instruction: `handle SIGSEGV nostop noprint pass` to the gdb configuration in order to not block the Java
debugger.
