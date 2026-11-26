#!/bin/sh

# This script to run the program locally and does not affect how
# CodeCrafters runs the program.

set -e # Exit early if any commands fail

exec java -jar ./target/codecrafters-http-server.jar "$@"
