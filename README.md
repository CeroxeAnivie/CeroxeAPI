# CeroxeAPI

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Version](https://img.shields.io/badge/version-2.0.2-green.svg)](https://central.sonatype.com/)

CeroxeAPI 是一组面向 Java 21 的模块化工具 API，提供网络通信、加密、日志、线程、系统检测、进程管理、邮件发送等常用能力。

项目按 Maven 多模块方式发布。你可以只导入自己需要的模块，而不是一次性引入所有功能。

## 版本说明

当前推荐版本：`2.0.2`

`2.0.2` 维持 Maven `groupId` 和 Java 包名统一为 `top.ceroxe.api`，所有模块版本统一为 `2.0.2`。`ceroxe-core-shared` 继续提供 Java 17 字节码基线，并新增平台线程任务工具 `TaskManager`；`ceroxe-core` 中的 `ThreadManager` 等 JVM 工具类保持 Java 21 虚拟线程能力。

如果你使用过旧版本，请注意：

- 从 `1.x` 升级时，依赖坐标和 Java `import` 需要从 `fun.ceroxe.api` 改为 `top.ceroxe.api`。
- `SecureSocket` 协议在早期版本中已经升级，旧版本客户端和新版本服务端不能混用。
- 字节传输 API 统一为 `sendBytes` / `receiveBytes`。
- 旧版本中的 `sendByte` / `receiveByte` 已移除。

## 环境要求

- JDK `21` 或更高版本
- Maven `3.9+`
- 推荐使用项目自带 Maven Wrapper

Windows：

```powershell
.\mvnw.cmd test
```

Linux/macOS：

```bash
./mvnw test
```

## 模块选择

| 模块 | Maven Artifact ID | 适合场景 |
| --- | --- | --- |
| Core Shared | `ceroxe-core-shared` | Java 17 基线的共享工具、加密、安全 Socket、平台线程 `TaskManager` |
| Core | `ceroxe-core` | 基础工具、加密、网络通信、日志、线程、控制台、配置读取 |
| Detector | `ceroxe-detector` | 获取系统、硬件、网络、Windows 进程相关信息 |
| Process | `ceroxe-process` | 启动并托管子进程 |
| Mail | `ceroxe-mail` | 发送 SMTP 邮件 |

## Maven 导入

### 只使用核心工具

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-core</artifactId>
    <version>2.0.2</version>
</dependency>
```

### 使用系统检测功能

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-detector</artifactId>
    <version>2.0.2</version>
</dependency>
```

### 使用进程管理功能

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-process</artifactId>
    <version>2.0.2</version>
</dependency>
```

### 使用邮件发送功能

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-mail</artifactId>
    <version>2.0.2</version>
</dependency>
```

## Gradle 导入

Kotlin DSL：

```kotlin
dependencies {
    implementation("top.ceroxe.api:ceroxe-core:2.0.2")
}
```

Groovy DSL：

```groovy
dependencies {
    implementation 'top.ceroxe.api:ceroxe-core:2.0.2'
}
```

其他模块同理，只需要替换 artifactId。

## 快速使用

### 加密工具

```java
import top.ceroxe.api.security.encryption.AESUtil;

AESUtil aes = new AESUtil(256);
byte[] encrypted = aes.encrypt("hello".getBytes());
byte[] plain = aes.decrypt(encrypted);
```

### 安全 Socket

服务端：

```java
import top.ceroxe.api.net.SecureServerSocket;
import top.ceroxe.api.net.SecureSocket;

try (SecureServerSocket server = new SecureServerSocket(25565);
     SecureSocket socket = server.accept()) {
    byte[] data = socket.receiveBytes();
    socket.sendBytes(data);
}
```

客户端：

```java
import top.ceroxe.api.net.SecureSocket;

try (SecureSocket socket = new SecureSocket("127.0.0.1", 25565)) {
    socket.sendBytes("hello".getBytes());
    byte[] response = socket.receiveBytes();
}
```

### Java 17 平台线程任务工具

`TaskManager` 位于 `ceroxe-core-shared`，使用守护平台线程实现，适合 Java 17 运行时和需要稳定字节码基线的依赖方。Java 21+ 项目如果需要虚拟线程，请使用 `ceroxe-core` 中的 `ThreadManager`。

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-core-shared</artifactId>
    <version>2.0.2</version>
</dependency>
```

```java
import top.ceroxe.api.thread.TaskManager;

try (TaskManager manager = new TaskManager(
        () -> doFirstJob(),
        () -> doSecondJob()
)) {
    manager.start();
}
```

### Java 21 虚拟线程任务工具

`ThreadManager` 位于 `ceroxe-core`，使用 Java 21 虚拟线程，适合高并发 I/O 型 JVM 应用。Java 17 运行时不要依赖 `ceroxe-core`。

```java
import top.ceroxe.api.thread.ThreadManager;

try (ThreadManager manager = new ThreadManager(
        () -> doFirstJob(),
        () -> doSecondJob()
)) {
    manager.start();
}
```

### 日志工具

```java
import top.ceroxe.api.print.log.Loggist;
import top.ceroxe.api.print.log.LogType;
import top.ceroxe.api.print.log.State;

try (Loggist log = new Loggist("logs/app.log")) {
    log.say(new State(LogType.INFO, "app", "started"));
}
```

### 系统检测

```java
import top.ceroxe.api.OshiUtils;

String os = OshiUtils.getOsString();
String cpu = OshiUtils.getCpuModel();
String memory = OshiUtils.getMemoryInfoReadable();
```

### 进程管理

```java
import top.ceroxe.api.ProcessContainer;

Process process = ProcessContainer.start("java", "-version");
process.waitFor();
```

### 邮件发送

```java
import top.ceroxe.api.EmailTool;

EmailTool.EmailConfig config = new EmailTool.EmailConfig(
        "smtp.example.com",
        587,
        "user@example.com",
        "password",
        null,
        null
);

EmailTool.send(config, "target@example.com", "Subject", "<b>Hello</b>");
```

## 本地构建

运行测试：

```bash
./mvnw test
```

安装到本地 Maven 仓库：

```bash
./mvnw clean install
```

跳过 GPG 的本地验证：

```bash
./mvnw clean verify -Dgpg.skip=true
```

## 发布说明

发布到 Maven Central 需要配置：

- Central Portal 凭据
- GPG 签名密钥
- Maven `settings.xml` 中的 `central` server

发布命令：

```bash
./mvnw clean deploy
```

## 许可证

CeroxeAPI 使用 [MIT License](https://opensource.org/licenses/MIT) 开源。
## Android Module Status

The Android library now lives in:

- `android/ceroxe-core-android`

Current status:

- the Android module builds successfully
- the Android module passes local unit tests and `connectedAndroidTest`
- the Android module has been submitted to Sonatype Central Portal for Maven Central publication
- Maven Central indexing may take some time before the artifact page becomes searchable

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-core-android</artifactId>
    <version>2.0.2</version>
</dependency>
```

Gradle Kotlin DSL after publication:

```kotlin
dependencies {
    implementation("top.ceroxe.api:ceroxe-core-android:2.0.2")
}
```

Gradle Groovy DSL after publication:

```groovy
dependencies {
    implementation 'top.ceroxe.api:ceroxe-core-android:2.0.2'
}
```
