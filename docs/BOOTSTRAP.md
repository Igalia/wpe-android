#  Bootstrap process

WPE Android depends on a considerable amount of libraries, 
including [libWPE](https://github.com/WebPlatformForEmbedded/libwpe) and 
[WPEWebKit](https://github.com/WebPlatformForEmbedded/WPEWebKit). To ease
the process of building and installing these dependencies we have a
[bootstrap script](../bootstrap.py) that can be run with the following command:

`python3 ./bootstrap.py <arch>`

where `<arch>` is the target architecture that you want to compile to.

This script takes care of fetching, building and installing all WPE Android dependencies.

The cross-compilation work is done by [Cerbero](https://gitlab.igalia.com/ferjm/cerbero)

After cloning Cerbero's source through git in the `build` folder, the process starts with
the following Cerbero command:

`./cerbero-uninstalled -c config/cross-android-<android_abi> -f wpewebkit`

where `<android_abi>` varies depending on the given architecture target.

The logic for this command is in the [WPEWebKit packaging recipe in Cerbero's repo](
https://gitlab.igalia.com/ferjm/cerbero/-/blob/b9c3b76efb1ed7e2fedfcd6838e638a194df2da8/packages/wpewebkit.package).

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

