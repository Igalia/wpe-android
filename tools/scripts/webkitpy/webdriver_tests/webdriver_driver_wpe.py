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

import os

from webkitpy.common.webkit_finder import WebKitFinder
from webkitpy.webdriver_tests.webdriver_driver import WebDriver, register_driver


class WebDriverWPE(WebDriver):

    def __init__(self, port_name, filesystem):
        super(WebDriverWPE, self).__init__(port_name, filesystem)

        self._binary_path = WebKitFinder(self._filesystem).path_from_webkit_base("tools", "scripts", "wpeviewdriver")

    def binary_path(self):
        return self._binary_path

    def browser_name(self):
        return "MiniBrowser"

    def browser_path(self):
        return "/bin/MiniBrowser"

    def browser_args(self):
        args = ['--automation']
        args.append('--headless')
        return args

    def browser_env(self):
        return os.environ.copy()

    def capabilities(self):
        capabilities = {'wpe:browserOptions': {
            'binary': self.browser_path(),
            'args': self.browser_args()}}

        browser_target_ip = self.browser_target_ip()
        if browser_target_ip:
            capabilities['wpe:browserOptions']['targetAddr'] = browser_target_ip
        browser_target_port = self.browser_target_port()
        if browser_target_port:
            capabilities['wpe:browserOptions']['targetPort'] = browser_target_port
        return capabilities

    def selenium_name(self):
        return 'WPEWebKit'


register_driver('wpe', WebDriverWPE)
