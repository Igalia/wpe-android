# Required for Python to search this directory for module files
import os
import platform
import sys

libraries = os.path.join(os.path.abspath(os.path.dirname(os.path.dirname(__file__))), 'libraries')
webkitcorepy_path = os.path.join(libraries, 'webkitcorepy')
if webkitcorepy_path not in sys.path:
    sys.path.insert(0, webkitcorepy_path)
import webkitcorepy
