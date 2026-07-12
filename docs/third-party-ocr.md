# OCR 第三方组件

验证码识别是可选构建能力。`autoCaptcha` 风味通过随 APK 打包的 ML Kit Text Recognition v2 拉丁文字模型在设备本地完成识别；验证码图片不会离开设备，也不依赖运行时下载识别模型。

`manualCaptcha` 风味不依赖或打包 ML Kit，只加载学校验证码图片供用户手动输入，同时关闭验证码自动回填与自动续登。
