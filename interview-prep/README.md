# Java RPC Project Interview Prep

这个目录用于沉淀当前 RPC 项目的 Java 开发工程师面试准备资料。所有后续整理内容都放在 `interview-prep/` 下，不修改业务代码。

## 建议阅读顺序

1. 先读 `01_project_overview.md`，建立项目整体认知。
2. 再读 `02_rpc_call_flow.md`，掌握一次 RPC 调用的完整链路。
3. 接着读 `03_technical_highlights.md`，提炼面试中的项目亮点。
4. 然后读 `04_interview_questions.md`，集中准备高频追问。
5. 按专项补强 `05` 到 `11`，重点理解 Netty、ZooKeeper、动态代理、协议、SPI、并发和优化。
6. 最后读 `12_resume_description.md` 和 `13_final_review_guide.md`，用于简历表达和面试前速记。

## 资料清单

| 顺序 | 文件 | 用途 | 状态 |
| --- | --- | --- | --- |
| 01 | `01_project_overview.md` | 项目整体介绍、模块划分、技术栈、1 分钟和 3 分钟项目介绍 | 已完成 |
| 02 | `02_rpc_call_flow.md` | 从客户端代理调用到服务端反射执行再返回响应的完整 RPC 调用链 | 已完成 |
| 03 | `03_technical_highlights.md` | 提炼项目技术亮点，并整理每个亮点的面试表达和可能追问 | 已完成 |
| 04 | `04_interview_questions.md` | 按类别整理项目高频面试题和参考答案 | 已完成 |
| 05 | `05_netty.md` | Netty 服务端、客户端、Pipeline、线程模型、粘包半包专项 | 已完成 |
| 06 | `06_zookeeper_registry.md` | ZooKeeper 服务注册、服务发现、Curator、服务下线专项 | 已完成 |
| 07 | `07_spring_annotation_proxy.md` | `@RpcService`、`@RpcReference`、BeanPostProcessor、JDK 动态代理专项 | 已完成 |
| 08 | `08_protocol_codec_serialization.md` | 自定义协议、编解码、序列化、压缩专项 | 已完成 |
| 09 | `09_spi_loadbalance_extensibility.md` | 自定义 SPI、负载均衡、一致性 Hash、扩展性设计专项 | 已完成 |
| 10 | `10_concurrency_async.md` | CompletableFuture、未完成请求表、Channel 复用、线程安全专项 | 已完成 |
| 11 | `11_project_improvements.md` | 项目缺陷、风险点、优化方案和面试应对话术 | 已完成 |
| 12 | `12_resume_description.md` | 简历项目经历的不同版本描述 | 已完成 |
| 13 | `13_final_review_guide.md` | 面试前最终复习手册，整合必背内容和高频问题 | 已完成 |

## 使用方式

后续每完成一份资料，都更新本 README 中对应文件的状态。建议状态使用：

- `待整理`
- `整理中`
- `已完成`

## 面试准备主线

这个项目面试时可以围绕这条主线展开：

> 基于 Netty 和 ZooKeeper 实现一个轻量级 RPC 框架，通过 Spring 自定义注解和 JDK 动态代理屏蔽远程调用细节，支持服务注册发现、自定义协议、序列化压缩、负载均衡和扩展机制。
