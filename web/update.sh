#!/bin/bash

set -e
cd $(dirname $0)

watchify -t babelify main.jsx -o ../src/main/resources/static/bundle.js
