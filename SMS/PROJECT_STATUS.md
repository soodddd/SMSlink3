# SmsLink 椤圭洰鐘舵€?
## 褰撳墠鐗堟湰
v0.4.0-alpha

## 椤圭洰姒傝堪
SmsLink 鏄竴涓?Android 搴旂敤锛岀敤浜庡湪璁惧闂村悓姝ョ煭淇°€侀€氱煡銆侀€氳瘽鍜屾枃浠朵紶杈撳姛鑳姐€?
---

## 宸插畬鎴愬姛鑳?
### 鏍稿績妯″潡 (Core)
- 鉁?**common**: 鍩虹宸ュ叿绫诲拰鎵╁睍鍑芥暟
- 鉁?**model**: 鏁版嵁妯″瀷瀹氫箟锛圡essage, Device, Notification, CallInfo, FileTransferInfo锛?- 鉁?**database**: Room 鏁版嵁搴撳疄鐜帮紙v3锛屽寘鍚?file_transfers 琛級
- 鉁?**preferences**: DataStore 閰嶇疆绠＄悊锛堥€氱煡杩囨护銆佹枃浠朵紶杈撹缃級

### 缃戠粶妯″潡 (Network)
- 鉁?**discovery**: NSD 璁惧鍙戠幇
- 鉁?**hotspot**: WiFi 鐑偣绠＄悊
- 鉁?**protocol**: 娑堟伅鍗忚瀹氫箟锛堟柊澧?FILE_TRANSFER_PROGRESS, FILE_TRANSFER_CANCEL锛?- 鉁?**transport**: TCP 浼犺緭灞傚疄鐜?
### 鍔熻兘妯″潡 (Feature)
- 鉁?**device**: 璁惧绠＄悊鍜岄厤瀵?- 鉁?**notification**: 閫氱煡鍚屾
- 鉁?**settings**: 璁剧疆鐣岄潰
- 鉁?**call**: 閫氳瘽鍔熻兘锛堜唬鐮佸畬鎴愶紝寰呮祴璇曪級
  - AudioCapture: 闊抽鎹曡幏锛?6kHz, 16-bit PCM锛?  - AudioPlayer: 闊抽鎾斁
  - AudioCodec: 闊抽缂栬В鐮?  - CallManager: 閫氳瘽鐘舵€佺鐞嗐€侀煶棰戞祦浼犺緭
  - CallBridge: UI 妗ユ帴灞?- 鉁?**transfer**: 鏂囦欢浼犺緭锛堜唬鐮佸畬鎴愶紝寰呮祴璇曪級
  - FileTransferManager: 鏀寔鏈€澶?20GB 鏂囦欢銆?4KB 鍒嗗潡銆佹柇鐐圭画浼?  - TransferRepository: 鏁版嵁鎸佷箙鍖?  - TransferBridge: UI 妗ユ帴灞?
### UI 妯″潡
- 鉁?**ui**: Jetpack Compose UI 瀹炵幇
  - 璁惧鍒楄〃鐣岄潰
  - 閫氱煡鍒楄〃鐣岄潰
  - 璁剧疆鐣岄潰
  - 鏂囦欢浼犺緭鐣岄潰
  - TransferViewModel 宸插鎺ョ湡瀹炴暟鎹?
### 闊抽妯″潡
- 鉁?**audio**: 闊抽鎹曡幏鍜屾挱鏀撅紙16kHz 閲囨牱鐜囷紝瀹炴椂娴佷紶杈擄級

### 闆嗘垚涓庤矾鐢?- 鉁?**MessageRouter**: 娑堟伅璺敱鍣紝杩炴帴 DeviceManager銆丆allManager銆丗ileTransferManager
- 鉁?**渚濊禆娉ㄥ叆**: Hilt 閰嶇疆瀹屾垚锛屾墍鏈夌鐞嗗櫒浣跨敤 @Singleton
- 鉁?**鏉冮檺绠＄悊**: PermissionHelper 鎵╁睍銆丳ermissionRequestManager

---

## 褰撳墠鐘舵€?
### 缂栬瘧鐘舵€?- 鈿狅笍 **閮ㄥ垎缂栬瘧閿欒寰呬慨澶?*
  - CallManager 涓?Message 鏋勯€犲凡淇锛堟坊鍔?messageId 鍙傛暟锛?  - MessageType 宸叉坊鍔犵己澶辩殑绫诲瀷锛團ILE_TRANSFER_PROGRESS, FILE_TRANSFER_CANCEL锛?  - MessageRouter 涓?ByteArray.indexOf 鎵╁睍鍑芥暟宸插疄鐜?  - ui 妯″潡宸叉坊鍔犲 feature:call 鍜?feature:transfer 鐨勪緷璧?  - 瀛樺湪 Gradle 鏂囦欢閿佸畾闂锛堥渶瑕侀噸鍚垨娓呯悊锛?
### 寰呰В鍐抽棶棰?1. **Gradle 鏋勫缓缂撳瓨闂**: 閮ㄥ垎妯″潡鐨?classes.jar 鏂囦欢琚崰鐢紝闇€瑕佸仠姝㈡墍鏈?Gradle daemon
2. **Hilt 缂栬瘧閿欒**: app 妯″潡鐨?hiltJavaCompileDebug 浠诲姟澶辫触锛屽彲鑳芥槸渚濊禆娉ㄥ叆閰嶇疆闂

---

## 涓嬩竴姝ヨ鍒?
### 绔嬪嵆浠诲姟锛堜紭鍏堢骇锛氶珮锛?1. **淇缂栬瘧閿欒**
   - 鍋滄鎵€鏈?Gradle daemon 杩涚▼
   - 娓呯悊鏋勫缓缂撳瓨
   - 妫€鏌?Hilt 渚濊禆娉ㄥ叆閰嶇疆
   - 纭繚鎵€鏈夋ā鍧楁垚鍔熺紪璇?
2. **楠岃瘉鍔熻兘闆嗘垚**
   - 娴嬭瘯 MessageRouter 娑堟伅鍒嗗彂
   - 楠岃瘉 CallManager 鍜?FileTransferManager 鐨勬秷鎭彂閫佸洖璋?   - 妫€鏌?ViewModel 鏁版嵁娴?
### 涓湡浠诲姟锛堜紭鍏堢骇锛氫腑锛?3. **瀹炴満娴嬭瘯**
   - 閫氳瘽鍔熻兘娴嬭瘯锛堥煶棰戞崟鑾枫€佹挱鏀俱€佷紶杈擄級
   - 鏂囦欢浼犺緭娴嬭瘯锛堝皬鏂囦欢銆佸ぇ鏂囦欢銆佹柇鐐圭画浼狅級
   - 鏉冮檺璇锋眰娴佺▼娴嬭瘯

4. **鎬ц兘浼樺寲**
   - 鏂囦欢浼犺緭鎬ц兘娴嬭瘯锛?0GB 澶ф枃浠讹級
   - 闊抽寤惰繜浼樺寲
   - 鍐呭瓨浣跨敤浼樺寲

### 闀挎湡浠诲姟锛堜紭鍏堢骇锛氫綆锛?5. **浠ｇ爜璐ㄩ噺**
   - 鍗曞厓娴嬭瘯瑕嗙洊
   - 闆嗘垚娴嬭瘯
   - 浠ｇ爜瀹℃煡鍜岄噸鏋?
6. **鏂囨。瀹屽杽**
   - API 鏂囨。鏇存柊
   - 鐢ㄦ埛鎵嬪唽
   - 鏁呴殰鎺掓煡鎸囧崡

---

## 鎶€鏈爤
- Kotlin 1.9.25
- Android SDK 35 (minSdk 33)
- Jetpack Compose (BOM 2024.12.01)
- Hilt 2.51.1 (渚濊禆娉ㄥ叆)
- Room 2.6.1 (鏁版嵁搴?
- DataStore 1.1.1 (閰嶇疆)
- Coroutines & Flow
- AudioRecord/AudioTrack (闊抽澶勭悊)

---

## 椤圭洰缁撴瀯
```
SMS/
鈹溾攢鈹€ app/                    # 涓诲簲鐢ㄦā鍧?鈹?  鈹斺攢鈹€ MessageRouter.kt   # 娑堟伅璺敱鍣?鈹溾攢鈹€ core/                   # 鏍稿績妯″潡
鈹?  鈹溾攢鈹€ common/            # 閫氱敤宸ュ叿
鈹?  鈹溾攢鈹€ model/             # 鏁版嵁妯″瀷
鈹?  鈹溾攢鈹€ database/          # 鏁版嵁搴擄紙v3锛?鈹?  鈹斺攢鈹€ preferences/       # 閰嶇疆绠＄悊
鈹溾攢鈹€ network/               # 缃戠粶妯″潡
鈹?  鈹溾攢鈹€ discovery/         # 璁惧鍙戠幇
鈹?  鈹溾攢鈹€ hotspot/          # 鐑偣绠＄悊
鈹?  鈹溾攢鈹€ protocol/         # 鍗忚瀹氫箟
鈹?  鈹斺攢鈹€ transport/        # 浼犺緭灞?鈹溾攢鈹€ feature/              # 鍔熻兘妯″潡
鈹?  鈹溾攢鈹€ device/          # 璁惧绠＄悊
鈹?  鈹溾攢鈹€ notification/    # 閫氱煡鍚屾
鈹?  鈹溾攢鈹€ call/           # 閫氳瘽鍔熻兘
鈹?  鈹溾攢鈹€ transfer/       # 鏂囦欢浼犺緭
鈹?  鈹斺攢鈹€ settings/       # 璁剧疆
鈹溾攢鈹€ audio/              # 闊抽妯″潡
鈹斺攢鈹€ ui/                 # UI 妯″潡
```

---

## 閲嶈鏂囨。绱㈠紩

### 鏋舵瀯涓庤璁?- **椤圭洰鏋舵瀯**: `C:\Users\forek\Desktop\ccwork\SMS\PROJECT_ARCHITECTURE.md`
  - 妯″潡鍒掑垎銆佷緷璧栧叧绯汇€佹妧鏈€夊瀷
  
- **鍗忚瑙勮寖**: `C:\Users\forek\Desktop\ccwork\SMS\docs\protocol\PROTOCOL_SPEC.md`
  - 娑堟伅鏍煎紡銆佹秷鎭被鍨嬨€侀€氫俊娴佺▼

- **UI/UX 璁捐**: `C:\Users\forek\Desktop\ccwork\SMS\docs\superpowers\specs\ui-ux-design.md`
  - 鐣岄潰璁捐銆佷氦浜掓祦绋嬨€佺敤鎴蜂綋楠?
### 瀹炴柦鎶ュ憡
- **UI 瀹炴柦鎶ュ憡**: `C:\Users\forek\Desktop\ccwork\SMS\docs\UI_IMPLEMENTATION_REPORT.md`
  - UI 妯″潡瀹炵幇缁嗚妭銆丆ompose 缁勪欢

- **閫氱煡瀹炴柦鎶ュ憡**: `C:\Users\forek\Desktop\ccwork\SMS\docs\NOTIFICATION_IMPLEMENTATION_REPORT.md`
  - 閫氱煡鍚屾鍔熻兘瀹炵幇

- **瀹屾暣瀹炴柦鎶ュ憡**: `C:\Users\forek\Desktop\ccwork\SMS\docs\COMPLETE_IMPLEMENTATION_REPORT.md`
  - 閫氳瘽銆佹枃浠朵紶杈撱€佹潈闄愮鐞嗙瓑鎵€鏈夋柊澧炲姛鑳界殑璇︾粏瀹炵幇

- **鏈€缁堥泦鎴愭姤鍛?*: `C:\Users\forek\Desktop\ccwork\SMS\docs\FINAL_INTEGRATION_REPORT.md`
  - 娑堟伅璺敱銆佷緷璧栨敞鍏ャ€佹ā鍧楅泦鎴?
### 寮€鍙戞寚鍗?- **寮€鍙戞寚鍗?*: `C:\Users\forek\Desktop\ccwork\SMS\docs\development\DEVELOPMENT_GUIDE.md`
  - 寮€鍙戠幆澧冮厤缃€佹瀯寤烘祦绋嬨€佷唬鐮佽鑼?
- **娴嬭瘯鎸囧崡**: `C:\Users\forek\Desktop\ccwork\SMS\docs\TESTING_GUIDE.md`
  - 娴嬭瘯绛栫暐銆佹祴璇曠敤渚嬨€佹祴璇曞伐鍏?
- **椤圭洰妫€鏌ユ竻鍗?*: `C:\Users\forek\Desktop\ccwork\SMS\docs\PROJECT_CHECKLIST.md`
  - 缂栬瘧妫€鏌ャ€佸姛鑳介獙璇併€侀泦鎴愭祴璇曘€佹€ц兘娴嬭瘯

### API 鏂囨。
- **鏍稿績 API**: `C:\Users\forek\Desktop\ccwork\SMS\docs\modules\CORE_API.md`
- **缃戠粶 API**: `C:\Users\forek\Desktop\ccwork\SMS\docs\modules\NETWORK_API.md`
- **鍔熻兘 API**: `C:\Users\forek\Desktop\ccwork\SMS\docs\modules\FEATURE_API.md`
- **闊抽 API**: `C:\Users\forek\Desktop\ccwork\SMS\docs\modules\AUDIO_API.md`
- **UI API**: `C:\Users\forek\Desktop\ccwork\SMS\docs\modules\UI_API.md`

### 淇瑙勮寖
- **AI 淇瑙勮寖**: `C:\Users\forek\Desktop\ccwork\SMS\AI_REVISION_SPEC.md`
  - AI 杈呭姪寮€鍙戠殑瑙勮寖鍜屾渶浣冲疄璺?
---

## 宸茬煡闂
1. **Gradle 鏋勫缓缂撳瓨**: 閮ㄥ垎妯″潡鐨?JAR 鏂囦欢琚崰鐢紝瀵艰嚧 clean 浠诲姟澶辫触
2. **Hilt 缂栬瘧閿欒**: app 妯″潡鐨?Hilt 娉ㄨВ澶勭悊澶辫触锛岄渶瑕佹鏌ヤ緷璧栭厤缃?3. **宸插簾寮?API**: 閮ㄥ垎缃戠粶 API 浣跨敤浜嗗凡搴熷純鐨勬柟娉曪紙NsdManager.resolveService, WifiConfiguration锛?4. **閫氳瘽鍔熻兘**: 闇€瑕佸疄鏈烘祴璇曢獙璇侀煶棰戣川閲忓拰寤惰繜
5. **鏂囦欢浼犺緭**: 闇€瑕佹€ц兘娴嬭瘯楠岃瘉澶ф枃浠朵紶杈撶ǔ瀹氭€?
---

## 鎶€鏈寒鐐?- **妯″潡鍖栨灦鏋?*: 娓呮櫚鐨勬ā鍧楀垝鍒嗭紝浣庤€﹀悎楂樺唴鑱?- **瀹炴椂闊抽娴?*: 16kHz 閲囨牱鐜囷紝浣庡欢杩熼煶棰戜紶杈?- **澶ф枃浠朵紶杈?*: 鏀寔鏈€澶?20GB锛?4KB 鍒嗗潡锛屾柇鐐圭画浼?- **娑堟伅璺敱**: 缁熶竴鐨勬秷鎭垎鍙戞満鍒讹紝鏄撲簬鎵╁睍
- **渚濊禆娉ㄥ叆**: Hilt 绠＄悊鎵€鏈夌粍浠剁敓鍛藉懆鏈?- **鍝嶅簲寮忕紪绋?*: Kotlin Flow 瀹炵幇鏁版嵁娴?
---

## 浠ｇ爜閫昏緫姒傝堪

### 娑堟伅娴佽浆
1. **璁惧鍙戠幇**: DeviceDiscovery 閫氳繃 NSD 鍙戠幇灞€鍩熺綉璁惧
2. **璁惧杩炴帴**: DeviceManager 寤虹珛 TCP 杩炴帴锛屽畬鎴愰厤瀵?3. **娑堟伅鍙戦€?*: 鍚勫姛鑳芥ā鍧楋紙CallManager, FileTransferManager锛夊垱寤?Message锛岄€氳繃鍥炶皟鍙戦€?4. **娑堟伅璺敱**: MessageRouter 鎺ユ敹娑堟伅锛屾牴鎹?MessageType 鍒嗗彂鍒板搴旀ā鍧?5. **娑堟伅澶勭悊**: 鍚勬ā鍧楀鐞嗘帴鏀跺埌鐨勬秷鎭紝鏇存柊鐘舵€?
### 閫氳瘽娴佺▼
1. **鏉ョ數妫€娴?*: CallManager 鐩戝惉绯荤粺閫氳瘽鐘舵€侊紙PhoneStateListener锛?2. **闊抽鎹曡幏**: AudioCapture 褰曞埗楹﹀厠椋庨煶棰戯紙16kHz PCM锛?3. **闊抽缂栫爜**: AudioCodec 缂栫爜闊抽鏁版嵁
4. **鏁版嵁浼犺緭**: 閫氳繃 MessageRouter 鍙戦€?CALL_AUDIO_DATA 娑堟伅
5. **闊抽瑙ｇ爜**: 鎺ユ敹绔?AudioCodec 瑙ｇ爜闊抽鏁版嵁
6. **闊抽鎾斁**: AudioPlayer 鎾斁瑙ｇ爜鍚庣殑闊抽

### 鏂囦欢浼犺緭娴佺▼
1. **浼犺緭璇锋眰**: 鐢ㄦ埛閫夋嫨鏂囦欢锛孎ileTransferManager 鍙戦€?FILE_TRANSFER_REQUEST
2. **鎺ユ敹纭**: 鎺ユ敹绔‘璁わ紝鍙戦€?FILE_TRANSFER_ACCEPT
3. **鍒嗗潡浼犺緭**: 鏂囦欢鍒嗘垚 64KB 鍧楋紝閫愬潡鍙戦€?FILE_TRANSFER_DATA
4. **杩涘害鏇存柊**: 瀹氭湡鍙戦€?FILE_TRANSFER_PROGRESS 鏇存柊杩涘害
5. **浼犺緭瀹屾垚**: 鎵€鏈夊潡浼犺緭瀹屾垚锛屽彂閫?FILE_TRANSFER_COMPLETE
6. **鏂偣缁紶**: 浼犺緭涓柇鏃讹紝璁板綍宸蹭紶杈撳亸绉婚噺锛屾敮鎸佹仮澶?
### 渚濊禆娉ㄥ叆
- **Application**: SmsLinkApplication 鍒濆鍖?Hilt
- **Module**: UiModule 鎻愪緵鎵€鏈夌鐞嗗櫒瀹炰緥锛園Singleton锛?- **Injection**: ViewModel 鍜?Bridge 閫氳繃 @Inject 娉ㄥ叆渚濊禆
- **Lifecycle**: Hilt 鑷姩绠＄悊缁勪欢鐢熷懡鍛ㄦ湡

---

## 鏈€鍚庢洿鏂?2024-01-XX (v0.4.0-alpha)

---

## 补充状态（2026-04-11）

### 已完成
- `./gradlew.bat :app:assembleDebug --console=plain` 已通过，测试 APK 已生成：
  - `C:\Users\forek\Desktop\ccwork\SMS\app\build\outputs\apk\debug\app-debug.apk`
- 文件传输链路已统一为“请求 -> 接收确认 -> 分块传输 -> 完成/取消”流程。
- `TransferScreen` 已补齐接收端 pending 任务的“接受 / 拒绝”入口。
- `MessageRouter`、`FileTransferManager`、`TransferViewModel`、`MainActivity` 的接口已对齐。
- 编译器提示的未使用参数 warning 已清理。

### 现有代码逻辑
- `MessageRouter` 负责把网络消息分发给 `CallManager` 和 `FileTransferManager`。
- `FileTransferManager` 发送端会先等待接收端确认，再开始传输；接收端按 `relativePath + offset + fileSize` 写入本地文件。
- `TransferViewModel` 从 `TransferBridge` 读取传输历史，并暴露接收确认动作给 UI。
- `TransferScreen` 会对 `RECEIVE + PENDING` 的传输项显示“接受 / 拒绝”按钮，同时展示方向、状态、进度和错误信息。

### 下一步：由电脑自动执行的真机测试
1. 使用 `adb devices` 确认真机在线且已授权。
2. 自动安装 `C:\Users\forek\Desktop\ccwork\SMS\app\build\outputs\apk\debug\app-debug.apk`。
3. 自动启动应用，检查启动页、主导航和传输页是否崩溃。
4. 自动跑传输回归：
   - 小文件发送
   - 文件夹发送
   - 接收端接受
   - 接收端拒绝
   - 取消传输
5. 自动采集 `logcat`、关键截图和失败步骤。
6. 如果设备未配对、权限被拒绝或出现崩溃，立即停止并输出具体错误点。

### 重要文档索引
- `C:\Users\forek\Desktop\ccwork\SMS\AI_REVISION_SPEC.md`
  - 当前 AI 代码修改指令集，定义本轮重构目标、约束和验证标准。
- `C:\Users\forek\Desktop\ccwork\SMS\AI_REVISION_SPEC1.md` 至 `C:\Users\forek\Desktop\ccwork\SMS\AI_REVISION_SPEC7.md`
  - 早期修订版本，记录同一任务目标的迭代草案，当前以 `AI_REVISION_SPEC.md` 为准。
- `C:\Users\forek\Desktop\ccwork\SMS\PROJECT_ARCHITECTURE.md`
  - 项目架构总览，说明模块边界、依赖关系和技术选型。
- `C:\Users\forek\Desktop\ccwork\SMS\docs\COMPLETE_IMPLEMENTATION_REPORT.md`
  - 完整实现报告，汇总通话、文件传输、权限与集成结果。
- `C:\Users\forek\Desktop\ccwork\SMS\docs\FINAL_INTEGRATION_REPORT.md`
  - 最终集成报告，记录模块接入和整体联调结论。
- `C:\Users\forek\Desktop\ccwork\SMS\docs\PROJECT_CHECKLIST.md`
  - 项目检查清单，适合编译、功能和集成的逐项验证。
- `C:\Users\forek\Desktop\ccwork\SMS\docs\TESTING_GUIDE.md`
  - 测试指南，包含测试思路、用例和验证方式。
