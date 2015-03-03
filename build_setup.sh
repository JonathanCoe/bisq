#!/bin/bash

# setup dirs
mkdir gui/updatefx
mkdir gui/updatefx/builds
mkdir gui/updatefx/builds/processed
mkdir gui/updatefx/site
mkdir gui/deploy

# create key/wallet. Copy wallet to UpdateProcess or use wallet form other OS build
java -jar ./updatefx/updatefx-app-1.2.jar --url=http://localhost:8000/ gui/updatefx

# start webserver for update data
# cd gui/updatefx/site
# python -m SimpleHTTPServer 8000

# create icons
# iconutil -c icns package/bitsquare.iconset
