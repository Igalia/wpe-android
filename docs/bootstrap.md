#  Bootstrap process

WPE Android depends on a considerable amount of libraries, 
including [libWPE](https://github.com/WebPlatformForEmbedded/libwpe) and 
[WPEWebKit](https://github.com/WebPlatformForEmbedded/WPEWebKit). To ease
the process of building and installing these dependencies we have a
[bootstrap script](../bootstrap.py) that can be run with the following command:

`python3 ./bootstrap.py --arch <arch> --build`

where `<arch>` is the target architecture that you want to compile to.

This script takes care of fetching, building and installing all WPE Android dependencies.

The cross-compilation work is done by [Cerbero](https://github.com/Igalia/cerbero)

After cloning Cerbero's source through git in the `build` folder, the process starts with
the following Cerbero command:

`./cerbero-uninstalled -c config/cross-android-<android_abi> -f wpewebkit`

where `<android_abi>` varies depending on the given architecture target.

The logic for this command is in the
[WPEWebKit packaging recipe in Cerbero's repo](https://github.com/Igalia/cerbero/blob/18f3346042abfa9455bc270019a3c337fae23018/packages/wpewebkit.package).

This command triggers the build for all WPEWebKit dependencies. After that WPEWebKit itself
is built. You can find the recipes for all dependencies and WPEWebKit build in the
`recipes` folder of Cerbero's repo.

Once WPEWebKit and all dependencies are built, the packaging step starts.
The list of assets that are packaged is defined by the `files` variable in the packaging recipe.
The syntax `wpeandroid:libs:stl` means 'from the recipe wpeandroid, include the libraries
(`files_libs` in the recipe) and the STL lib (`files_stl` in the recipe).
You can think of the `:` separating the file types as commas in a list. For most recipes
we only care about the libraries, except for WPEWebKit from which we want everything.

The packaging step results in two different tar files. One containing the runtime assets
and another one with the development assets. The content of these tar files is extracted
in the `build/sysroot` folder.

After that we are done with Cerbero and back into the bootstrap script.

Before being able to use the generated libraries, we need to work around a limitation of
Android's package manager. The package manager only unpacks libxxx.so named libraries so
any library with versioning (i.e. libxxx.so.1) will be ignored. To fix this we rename all
versioned libraries to the libxxx.so form. For example, a library named libfoo.so.1 will
become libfoo_1.so. Apart from renaming the actual library files, we need to tweak the
SONAME and NEEDED values as well to reflect the name changes. We also need to take care of
the symbolic links to reflect the naming changes.


The final step is to copy the needed headers and processed libraries into its corresponding
location within the `wpe` project. This is done by the `__install_deps` function.

