pipeline {
    agent any

    environment {
        EXPORT_PATH       = "/Users/agi00114/Desktop/JenKins文件夹/pack_pro"
        EXPORT_OPTIONS    = "${EXPORT_PATH}/export_options.plist"
        OUTPUT_DIR        = "/Users/agi00114/Desktop/JenKins文件夹/APP"

        BUILD_NAME        = "${env.VERSION ?: '1.0.0'}"
        BUILD_NUMBER      = "${env.BUILDNUM ?: '1'}"
        APP_ENV           = "${env.APP_ENV ?: 'test'}"   // test / prod

        PGYER_API_KEY     = "cc4f2c2299fb7ccb2ce7b1c9581e4d01"
        PGYER_UPDATE_DESC = "${env.PGYER_UPDATE_DESC ?: '自动构建上传'}"
        PGYER_PASSWORD    = "initiai" // 安装密码

        DINGTALK_WEBHOOK  = "https://oapi.dingtalk.com/robot/send?access_token=057c702cdb1896282659cd07439846fd07ec052cf599883260c08f289f2cd89f"
    }

    stages {

        stage('初始化仓库') {
            steps {
                dir('facesong_flutter') {
                    echo "🚀 [1/6] 废弃本地变更并拉取最新代码..."
                    sh '''
                        git reset --hard
                        git clean -fd
                    '''
                    checkout([$class: 'GitSCM',
                        branches: [[name: "$GIT_REF"]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        userRemoteConfigs: [[
                            url: 'git@codeup.aliyun.com:6710bdc09d3c82efe37b13cc/facesong/facesong_flutter.git',
                            credentialsId: '7b783947-145e-45f0-b030-064f0056b69b'
                        ]]
                    ])
                    echo "📄 最近提交日志："
                    sh "git log -5 --pretty=format:'%h %an %ad %s' --date=short"
                }
            }
        }

        stage('设置 APP 参数') {
            steps {
                script {
                    if (env.APP_ENV == "test") {
                        env.APP_NAME = "音潮-测试"
                        env.DART_DEFINE_FILE = "${EXPORT_PATH}/prod_test.env"
                    } else if (env.APP_ENV == "prod") {
                        env.APP_NAME = "音潮-生产回归"
                        env.DART_DEFINE_FILE = "${EXPORT_PATH}/prod.env"
                    } else {
                        error "❌ 未知 APP_ENV: ${env.APP_ENV}，请指定 test 或 prod"
                    }
                    echo "🎯 当前环境: ${APP_ENV}, APP_NAME=${APP_NAME}, DART_DEFINE_FILE=${DART_DEFINE_FILE}"
                }
            }
        }

        stage('修改 APP 安装名称') {
            steps {
                dir('facesong_flutter') {
                    echo "✏️ [1.5/6] 修改 iOS/Android 安装名称（不提交）"
                    sh """
                        /usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName '${APP_NAME}'" ios/Runner/Info.plist
                        sed -i '' "/create('production') {/,/}/ s/resValue 'string', 'app_name', '音潮'/resValue 'string', 'app_name', '${APP_NAME}'/" android/app/build.gradle
                    """
                }
            }
        }

        stage('Flutter 初始化') {
            steps {
                dir('facesong_flutter') {
                    echo "🧹 [2/6] flutter clean & pub get, 替换签名文件"
                    sh '''
                        rm -f ios/Podfile.lock
                        fvm flutter clean
                        fvm flutter pub get

                        cp "$EXPORT_PATH/key.properties" android/app/key.properties
                        cp "$EXPORT_PATH/release.keystore" android/app/release.keystore

                        if [[ -f android/app/key.properties && -f android/app/release.keystore ]]; then
                            echo "✅ 签名文件替换成功"
                        else
                            echo "❌ 签名文件替换失败"
                            exit 1
                        fi
                    '''
                }
            }
        }

        stage('构建 iOS IPA') {
            when { expression { return env.BUILD_IOS == "true" } }
            steps {
                dir('facesong_flutter') {
                    echo "🍎 [3/6] 构建 iOS IPA"
                    sh '''
                        fvm flutter build ipa \
                            --flavor production \
                            --release \
                            --dart-define-from-file="$DART_DEFINE_FILE" \
                            --dart-define=WATERMARK=true \
                            --dart-define=DEV_CONFIG=true \
                            --export-options-plist="$EXPORT_OPTIONS" \
                            --build-name="$BUILD_NAME" \
                            --build-number="$BUILD_NUMBER"
                    '''
                }
            }
        }

        stage('上传 iOS 到蒲公英') {
            when { expression { return env.BUILD_IOS == "true" } }
            steps {
                dir('facesong_flutter') {
                    script {
                        def ipaPath = "build/ios/ipa/facesong_flutter.ipa"
                        def targetIpa = "${OUTPUT_DIR}/${APP_NAME}_${BUILD_NAME}_${BUILD_NUMBER}.ipa"
                        sh "cp \"${ipaPath}\" \"${targetIpa}\""
                        echo "📤 上传 iOS 到蒲公英..."

                        def uploadResult = sh(
                            script: """
                                curl -s -F "file=@${targetIpa}" \
                                     -F "_api_key=${PGYER_API_KEY}" \
                                     -F "buildUpdateDescription=${PGYER_UPDATE_DESC}" \
                                     "https://www.pgyer.com/apiv2/app/upload"
                            """,
                            returnStdout: true
                        ).trim()

                        echo "返回结果: ${uploadResult}"
                        def json = readJSON text: uploadResult
                        if (json.code != 0) {
                            env.IOS_FAILED = "true"
                            error "❌ iOS 上传失败: ${json.message}"
                        }

                        env.IOS_BUILDK = json.data.buildKey
                        env.IOS_QR = "https://www.pgyer.com/app/qrcode/${env.IOS_BUILDK}" // 直接图片
                        env.IOS_INSTALL = "https://api.pgyer.com/apiv2/app/install?_api_key=${PGYER_API_KEY}&buildKey=${env.IOS_BUILDK}&buildPassword=${PGYER_PASSWORD}"
                    }
                }
            }
        }

        stage('发送 iOS 钉钉通知') {
            when { expression { return env.BUILD_IOS == "true" } }
            steps {
                script {
                    def timeStr = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("Asia/Shanghai"))
                    // 使用草料二维码生成二维码
                    def encodedUrl = java.net.URLEncoder.encode(env.IOS_INSTALL, "UTF-8")
                    def qrUrl = "https://api.2dcode.biz/v1/create-qr-code?data=${encodedUrl}"
                    echo "二维码地址: ${qrUrl}"
                    def markdownText = """
### 🎉 Jenkins 构建完成（iOS）：${APP_NAME}

- **构建版本**：${BUILD_NAME} (${BUILD_NUMBER})
- **构建分支**：${env.GIT_REF ?: '未知'}
- **完成时间**：${timeStr}

#### 📦 构建结果
- ✅ iOS 构建完成
- 蒲公英二维码: ![](${env.IOS_QR})
- 点击安装: [安装](${env.IOS_INSTALL})
- [扫码安装](${qrUrl})
                    """.stripIndent()

                    def payload = """
                    {
                        "msgtype": "markdown",
                        "markdown": {
                            "title": "Jenkins 构建完成（iOS）：${APP_NAME}",
                            "text": "${markdownText.replace('"', '\\"').replace("\n", "\\n")}"
                        },
                        "at": { "isAtAll": ${env.AT_ALL} }
                    }
                    """
                    writeFile file: 'dingding_ios_payload.json', text: payload
                    sh "curl -s -X POST '${DINGTALK_WEBHOOK}' -H 'Content-Type: application/json' -d @dingding_ios_payload.json"
                }
            }
        }
        
        stage('修改 MainActivity launchMode') {
            when { expression { return env.BUILD_ANDROID == "true" && env.UM_LOG == "true" } }
            steps {
                dir('facesong_flutter') {
                    echo "✏️ 修改 MainActivity 的 launchMode 为 standard"
                    sh '''
                        awk '
                            /android:name=".MainActivity"/ { inActivity=1 }
                            inActivity && /android:launchMode=/ {
                                sub(/android:launchMode="singleInstance"/, "android:launchMode=\\"standard\\"")
                            }
                            inActivity && /<\\/activity>/ { inActivity=0 }
                            { print }
                        ' android/app/src/main/AndroidManifest.xml > AndroidManifest.tmp && mv AndroidManifest.tmp android/app/src/main/AndroidManifest.xml
                    '''
                }
            }
        }

        stage('构建 Android APK') {
            when { expression { return env.BUILD_ANDROID == "true" } }
            steps {
                dir('facesong_flutter') {
                    echo "🤖 [4/6] 构建 Android APK"
                    sh '''
                        sed -i '' 's/minSdk = flutter\\.minSdkVersion/minSdk = 24/' android/app/build.gradle
                        fvm flutter build apk \
                            --flavor production \
                            --release \
                            --target-platform android-arm64 \
                            --dart-define-from-file="$DART_DEFINE_FILE" \
                            --dart-define=WATERMARK=true \
                            --dart-define=DEV_CONFIG=true \
                            --build-name="$BUILD_NAME" \
                            --build-number="$BUILD_NUMBER"
                    '''
                }
            }
        }

        stage('上传 APK 到蒲公英') {
            when { expression { return env.BUILD_ANDROID == "true" } }
            steps {
                dir('facesong_flutter') {
                    script {
                        def apkPath = "build/app/outputs/flutter-apk/app-production-release.apk"
                        def targetApk = "${OUTPUT_DIR}/${APP_NAME}_${BUILD_NAME}_${BUILD_NUMBER}.apk"
                        sh "cp \"${apkPath}\" \"${targetApk}\""
                        echo "📤 上传 Android 到蒲公英..."

                        def uploadResult = sh(
                            script: """
                                curl -s -F "file=@${targetApk}" \
                                     -F "_api_key=${PGYER_API_KEY}" \
                                     -F "buildUpdateDescription=${PGYER_UPDATE_DESC}" \
                                     "https://www.pgyer.com/apiv2/app/upload"
                            """,
                            returnStdout: true
                        ).trim()

                        echo "返回结果: ${uploadResult}"
                        def json = readJSON text: uploadResult
                        if (json.code != 0) {
                            env.ANDROID_FAILED = "true"
                            error "❌ Android 上传失败: ${json.message}"
                        }

                        env.ANDROID_BUILDK = json.data.buildKey
                        env.ANDROID_QR = "https://www.pgyer.com/app/qrcode/${env.ANDROID_BUILDK}" // 图片
                        env.ANDROID_INSTALL = "https://api.pgyer.com/apiv2/app/install?_api_key=${PGYER_API_KEY}&buildKey=${env.ANDROID_BUILDK}&buildPassword=${PGYER_PASSWORD}"
                    }
                }
            }
        }

        

        stage('发送 Android 钉钉通知') {
            when { expression { return env.BUILD_ANDROID == "true" } }
            steps {
                script {
                    def timeStr = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("Asia/Shanghai"))
                    // 使用草料二维码生成二维码
                    def encodedUrl = java.net.URLEncoder.encode(env.ANDROID_INSTALL, "UTF-8")
                    def qrUrl = "https://api.2dcode.biz/v1/create-qr-code?data=${encodedUrl}"
                     echo "二维码地址: ${qrUrl}"
                    def markdownText = """
### 🎉 Jenkins 构建完成（Android）：${APP_NAME}

- **构建版本**：${BUILD_NAME} (${BUILD_NUMBER})
- **构建分支**：${env.GIT_REF ?: '未知'}
- **完成时间**：${timeStr}

#### 📦 构建结果
- ✅ Android 构建完成
- 蒲公英二维码: ![](${env.ANDROID_QR})
- 点击安装: [安装](${env.ANDROID_INSTALL})
- [扫码安装](${qrUrl})
                    """.stripIndent()

                    def payload = """
                    {
                        "msgtype": "markdown",
                        "markdown": {
                            "title": "Jenkins 构建完成（Android）：${APP_NAME}",
                            "text": "${markdownText.replace('"', '\\"').replace("\n", "\\n")}"
                        },
                        "at": { "isAtAll": ${env.AT_ALL} }
                    }
                    """
                    writeFile file: 'dingding_android_payload.json', text: payload
                    sh "curl -s -X POST '${DINGTALK_WEBHOOK}' -H 'Content-Type: application/json' -d @dingding_android_payload.json"
                }
            }
        }
    }

    post {
        failure {
            script {
                def failedTargets = []
                if (env.IOS_FAILED == "true") { failedTargets << "iOS" }
                if (env.ANDROID_FAILED == "true") { failedTargets << "Android" }
                if (failedTargets.isEmpty()) { failedTargets << "未知平台" }

                def timeStr = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("Asia/Shanghai"))
                def markdownText = """
### ❌ Jenkins 构建失败：${APP_NAME}

- **失败平台**：${failedTargets.join(", ")}
- **构建版本**：${BUILD_NAME} (${BUILD_NUMBER})
- **构建分支**：${env.GIT_REF ?: '未知'}
- **失败时间**：${timeStr}

请前往 Jenkins 查看详细日志并处理。
                """.stripIndent()

                def payload = """
                {
                    "msgtype": "markdown",
                    "markdown": {
                        "title": "Jenkins 构建失败：${APP_NAME}",
                        "text": "${markdownText.replace('"', '\\"').replace("\n", "\\n")}"
                    },
                    "at": { "isAtAll": false }
                }
                """
                writeFile file: 'dingding_failure_payload.json', text: payload
                sh "curl -s -X POST '${DINGTALK_WEBHOOK}' -H 'Content-Type: application/json' -d @dingding_failure_payload.json"
            }
        }
    }
}
