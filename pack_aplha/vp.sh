#!/bin/bash

KEYSTORE_PATH="release.keystore"
KEYSTORE_PASS="yinchaoalpha@2025!"
ALIAS="yinchaoapp-alpha"
KEY_PASS="yinchaoalpha@2025!"

# 尝试列出指定 alias 信息
keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$ALIAS" -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" > /dev/null 2>&1

if [ $? -eq 0 ]; then
    echo "✅ Keystore 和 key 密码正确"
else
    echo "❌ Keystore 或 key 密码错误"
fi
