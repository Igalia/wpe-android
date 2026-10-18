# WebDriver tests

The tests (Selenium and W3C/WPT) and their runner are WebKit's: `tools/scripts/run-webdriver-tests` fetches
them from WebKit, applies the patches in `patches/` and runs them against the WebDriver app on a device or
emulator. Only these files live here:

* `upstream.json`: the WebKit revision and the paths to fetch. Keep the revision at the WebKit commit of the
  prebuilt packages (`default_version` in `tools/scripts/bootstrap.py`), so the tests match the WebKit they run
  against.
* `patches/`: what wpe-android changes in WebKit's files (running on Android through
  `tools/scripts/wpeviewdriver`, client timeouts, and so on), as `git format-patch` files.
* `TestExpectations.json`: wpe-android's expectations, applied on top of WebKit's
  `WebDriverTests/TestExpectations.json`. It only holds what differs on Android from WebKit's expectations for
  WPE, under the `wpe` platform.
* `requirements.txt`: the Python packages the tests need.

The fetch is a sparse, shallow clone of only those paths into `build/webdriver-tests/` (a few seconds and about
150 MB), and it is done again only when `upstream.json` or the patches change. It never deletes work in that
checkout that is not exported to `patches/`: it stops and says what to do instead. Run
`tools/scripts/fetch-webdriver-tests --force` to drop that work and start again.

## Running the tests

```
python3 -m venv .venv
.venv/bin/pip install -r webdrivertests/requirements.txt
.venv/bin/python tools/scripts/run-webdriver-tests [--json-output=FILE] [test...]
```

The WebDriver app (`tools/webdriver`) has to be installed on the device. Tests are given relative to
`WebDriverTests` (for example `imported/w3c/webdriver/tests/classic/get_title`). A directory in the expectations
skips every test in it.

## Moving to another WebKit revision

1. Change `revision` in `upstream.json`.
2. Run the tests. `build/webdriver-tests` is a git checkout of that revision with the patches as commits: if a
   patch no longer applies (the fetch stops in the middle of its `git am`), or one has to change, rebase or amend
   them there, then replace the series: remove `webdrivertests/patches/*.patch` and export them again with
   `git format-patch --no-signature -o ../../webdrivertests/patches refs/wpe-android/base..`. The tests run from
   that checkout again once its commits are the exported ones.
3. Update `TestExpectations.json` from the results of several runs: drop the entries WebKit's file now covers,
   and add one only where the result on Android is not what WebKit expects.
