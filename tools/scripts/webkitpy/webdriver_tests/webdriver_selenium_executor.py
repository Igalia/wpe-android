# Copyright (C) 2017 Igalia S.L.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions
# are met:
# 1.  Redistributions of source code must retain the above copyright
#     notice, this list of conditions and the following disclaimer.
# 2.  Redistributions in binary form must reproduce the above copyright
#     notice, this list of conditions and the following disclaimer in the
#     documentation and/or other materials provided with the distribution.
#
# THIS SOFTWARE IS PROVIDED BY APPLE INC. AND ITS CONTRIBUTORS ``AS IS'' AND ANY
# EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
# WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL APPLE INC. OR ITS CONTRIBUTORS BE LIABLE FOR ANY
# DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
# (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
# LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
# ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
# (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
# SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

import logging
import socket
import subprocess

pytest_runner = None

SELENIUM_WEB_SERVER_PORT = 8000


def do_delayed_imports():
    global pytest_runner
    import webkitpy.webdriver_tests.pytest_runner as pytest_runner


_log = logging.getLogger(__name__)


class WebDriverSeleniumExecutor(object):

    def __init__(self, driver):
        self._driver_name = driver.selenium_name()
        self._env = driver.browser_env()

        self._args = ['--driver=%s' % self._driver_name, '--driver-binary=%s' % driver.binary_path()]
        browser_path = driver.browser_path()
        if browser_path:
            self._args.extend(['--browser-binary=%s' % browser_path])
        browser_target_ip = driver.browser_target_ip()
        if browser_target_ip:
            self._args.extend(['--browser-target-ip=%s' % browser_target_ip])
        browser_target_port = driver.browser_target_port()
        if browser_target_port:
            self._args.extend(['--browser-target-port=%s' % browser_target_port])
        browser_args = driver.browser_args()
        if browser_args:
            self._args.extend(['--browser-args=%s' % ' '.join(browser_args)])

        if pytest_runner is None:
            do_delayed_imports()

        self._setup_adb_reverse()

    def _adb_run(self, args):
        cmd = ['adb']
        cmd.extend(args)
        _log.info(' '.join(cmd))
        subprocess.check_call(cmd)

    def _check_web_server_port_is_free(self):
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('127.0.0.1', SELENIUM_WEB_SERVER_PORT))
        except OSError as error:
            raise RuntimeError('Port %d is taken (%s). The fixture web server would fall back to '
                               'the next free port, which is not the one reversed onto the device, '
                               'and every test would fail to load its page.'
                               % (SELENIUM_WEB_SERVER_PORT, error))
        finally:
            probe.close()

    def _setup_adb_reverse(self):
        self._check_web_server_port_is_free()
        self._adb_run(['wait-for-device'])
        self._adb_run(['reverse', 'tcp:%d' % SELENIUM_WEB_SERVER_PORT,
                       'tcp:%d' % SELENIUM_WEB_SERVER_PORT])

    def collect(self, directory):
        return pytest_runner.collect(directory, self._args, self._driver_name)

    def run(self, test, timeout, expectations):
        return pytest_runner.run(test, self._args, timeout, self._env, expectations, self._driver_name)
