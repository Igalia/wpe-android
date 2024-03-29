# Licensed to the Software Freedom Conservancy (SFC) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The SFC licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from selenium.webdriver.common.desired_capabilities import DesiredCapabilities
from selenium.webdriver.common.options import ArgOptions


class Options(ArgOptions):
    KEY = 'wpe:browserOptions'

    def __init__(self):
        super(Options, self).__init__()
        self._binary_location = ''
        self._caps = DesiredCapabilities.WPEWEBKIT.copy()
        self._target_ip = None
        self._target_port = None

    @property
    def capabilities(self):
        return self._caps

    def set_capability(self, name, value):
        """Sets a capability."""
        self._caps[name] = value

    @property
    def binary_location(self):
        """
        Returns the location of the browser binary otherwise an empty string
        """
        return self._binary_location

    @binary_location.setter
    def binary_location(self, value):
        """
        Allows you to set the browser binary to launch

        :Args:
         - value : path to the browser binary
        """
        self._binary_location = value

    @property
    def target_ip(self):
        """
        :Returns: The IP address of existing browser instance running in automation mode othewise None
        """
        return self._target_ip

    @target_ip.setter
    def target_ip(self, value):
        """
        Allows you to set the tcp port of existing browser instance running in automation mode

        :Args:
         - value : ip address
        """
        self._target_ip = value

    @property
    def target_port(self):
        """
        :Returns: The IP port number of existing browser instance running in automation mode othewise None
        """
        return self._target_port

    @target_port.setter
    def target_port(self, value):
        """
        Allows you to set the IP port number of existing browser instance running in automation mode

        :Args:
         - value : port number
        """
        self._target_port = value

    def to_capabilities(self):
        """
        Creates a capabilities with all the options that have been set and
        returns a dictionary with everything
        """
        caps = self._caps

        browser_options = {}
        if self.binary_location:
            browser_options["binary"] = self.binary_location
        if self.arguments:
            browser_options["args"] = self.arguments
        if self.target_ip:
            browser_options["targetAddr"] = self.target_ip
        if self.target_port:
            browser_options["targetPort"] = int(self.target_port)

        caps[Options.KEY] = browser_options

        return caps
