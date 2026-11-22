#!/bin/sh
# Starts the demo simulator. Arguments: <demo jar> <port>. SMOKER_JAVA names the java binary (default: java).
exec "${SMOKER_JAVA:-java}" -cp "$1" smoker.demo.DemoSimulator "$2"
