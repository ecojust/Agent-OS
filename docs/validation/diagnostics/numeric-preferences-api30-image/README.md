# API30 实际系统镜像的静态核验

为定位运行40736683857的提供方发现失败，从Google官方地址下载Android 11 default x86_64系统镜像，并核对官方SHA-1。镜像的实际构建指纹与该运行归档设备一致：`Android/sdk_phone_x86_64/generic_x86_64:11/RSR1.210722.013.A2/10067904:userdebug/test-keys`。官方仓库元数据及ZIP文件名标revision 11，ZIP内原始 `source.properties` 写revision 10；两者如实保留，不自行改正。

只读提取镜像内DocumentsUI.apk并用实际SDK 35.0.0 aapt2解析，完整清单显示：`com.android.documentsui`、versionCode 30、targetSdk 30，11项请求权限中没有 `QUERY_ALL_PACKAGES`，没有sharedUserId；查询Intent只有SEND、SEND_MULTIPLE、VIEW。对应特权配置也没有添加该权限。框架原清单将此权限定义为normal。

[清单](manifest.json)记录原始镜像和APK摘要、对应归档来源、每个公开文件的大小与SHA-256。DocumentsUI的完整aapt2输出、原始权限配置、build.prop与source.properties按原字节保留；框架权限为连续原输出摘录，行号与完整输出摘要随清单记录。没有提交系统镜像或第三方APK，也没有把静态文件检查称为启动模拟器后的观测。

这证明该精确镜像的系统选择器确实没有声明查询全部包的权限，不能单凭较新AOSP标签已有该声明而排除包可见性问题。它仍不等于实际运行时授权查询或对原失败的完整因果证明。测试APK的安装可见性设置及实际SAF导入导出效果，由后续精确源码回归另行验证。
