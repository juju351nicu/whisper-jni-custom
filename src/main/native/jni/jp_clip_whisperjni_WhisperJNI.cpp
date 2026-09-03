// jp_clip_whisperjni_WhisperJNI.cpp
//
// whisper.cpp と jp.clip.whisperjni.WhisperJNI を橋渡しする JNI 実装。
//
// ファイル構成
//   1. ハンドル管理      Java に渡す整数 ID とネイティブポインタの対応表（スレッド安全）
//   2. Java 例外         C++ から Java 例外を送出するヘルパ
//   3. 文字列変換        Java String <-> UTF-8（NewStringUTF は使わない。後述）
//   4. フィールド読み出し WhisperContextParams / WhisperTranscriptionParams を C 構造体へ写す
//   5. 文法              WhisperGrammar を whisper_full_params へ適用する
//   6. WhisperToken         whisper_token_data -> jp.clip.whisperjni.WhisperToken
//   7. ログ              whisper.cpp / ggml のログを SLF4J Logger へ転送する
//   8. JNI エクスポート   ヘッダ jp_clip_whisperjni_WhisperJNI.h と 1 対 1
//
// Java 側との取り決め
//   - ハンドルを返す関数は失敗時に -1 を返す。Java 側（WhisperJNI）が例外へ変換する。
//   - 添字の範囲外は IndexOutOfBoundsException("Index out of range")。文言はテストが検証している。
//   - Java のフィールド名を GetFieldID で参照している。Java 側の名前を変えたら
//     readContextParams / readTranscriptionParams / applyGrammar も同時に変えること。
//     不一致は実行時の NoSuchFieldError として Java 側へ伝わる（クラッシュはしない）。
//   - パッケージ名を変えたら、このファイル内の "jp/clip/whisperjni/..." 文字列も変えること。
//
// 文字列について
//   JNI の NewStringUTF / GetStringUTFChars は「修正 UTF-8」を扱う。通常の UTF-8 とは
//   補助面の文字（絵文字や一部の漢字）の表現が異なるため、日本語の文字起こし結果を
//   安全に渡すには String(byte[], "UTF-8") / String.getBytes("UTF-8") を経由する。

#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "jp_clip_whisperjni_WhisperJNI.h"
#include "whisper.h"
#include "grammar-parser.h"

namespace
{

// ============================================================================
// 1. ハンドル管理
// ============================================================================

// ネイティブポインタを Java へ直接渡さず、採番した整数 ID で参照させるための表。
// 3 種類（context / state / grammar）それぞれが独立した表を持つ。
template <typename T>
class HandleTable
{
public:
  jint add(T *pointer)
  {
    std::lock_guard<std::mutex> lock(mutex_);
    jint id = nextId_++;
    entries_.emplace(id, pointer);
    return id;
  }

  // 見つからなければ nullptr
  T *find(jint id)
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = entries_.find(id);
    return it == entries_.end() ? nullptr : it->second;
  }

  // 表から外して返す。見つからなければ nullptr
  T *remove(jint id)
  {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = entries_.find(id);
    if (it == entries_.end())
    {
      return nullptr;
    }
    T *pointer = it->second;
    entries_.erase(it);
    return pointer;
  }

private:
  std::mutex mutex_;
  std::unordered_map<jint, T *> entries_;
  jint nextId_ = 1; // -1 は失敗を表すので使わない
};

HandleTable<whisper_context> contexts;
HandleTable<whisper_state> states;
HandleTable<grammar_parser::parse_state> grammars;

// ============================================================================
// 2. Java 例外
// ============================================================================

void throwJava(JNIEnv *env, const char *className, const std::string &message)
{
  if (env->ExceptionCheck())
  {
    return; // 既に保留中の例外があれば上書きしない
  }
  jclass exceptionClass = env->FindClass(className);
  if (exceptionClass)
  {
    env->ThrowNew(exceptionClass, message.c_str());
    env->DeleteLocalRef(exceptionClass);
  }
}

whisper_context *requireContext(JNIEnv *env, jint ref)
{
  whisper_context *context = contexts.find(ref);
  if (!context)
  {
    throwJava(env, "java/lang/IllegalStateException", "Unknown whisper context handle: " + std::to_string(ref));
  }
  return context;
}

whisper_state *requireState(JNIEnv *env, jint ref)
{
  whisper_state *state = states.find(ref);
  if (!state)
  {
    throwJava(env, "java/lang/IllegalStateException", "Unknown whisper state handle: " + std::to_string(ref));
  }
  return state;
}

// 文言 "Index out of range" はテストが検証しているので変えないこと
bool checkIndex(JNIEnv *env, int count, jint index)
{
  if (index < 0 || index >= count)
  {
    throwJava(env, "java/lang/IndexOutOfBoundsException", "Index out of range");
    return false;
  }
  return true;
}

// ============================================================================
// 3. 文字列変換
// ============================================================================

// String(byte[], String charsetName) と String.getBytes(String charsetName) を JNI_OnLoad で解決して保持する
struct StringBridge
{
  jclass stringClass = nullptr;
  jmethodID fromBytes = nullptr; // <init>([BLjava/lang/String;)V
  jmethodID toBytes = nullptr;   // getBytes(Ljava/lang/String;)[B
  jstring utf8 = nullptr;        // "UTF-8"
} stringBridge;

bool initStringBridge(JNIEnv *env)
{
  jclass localClass = env->FindClass("java/lang/String");
  if (!localClass)
  {
    return false;
  }
  stringBridge.stringClass = static_cast<jclass>(env->NewGlobalRef(localClass));
  stringBridge.fromBytes = env->GetMethodID(localClass, "<init>", "([BLjava/lang/String;)V");
  stringBridge.toBytes = env->GetMethodID(localClass, "getBytes", "(Ljava/lang/String;)[B");
  env->DeleteLocalRef(localClass);

  jstring localUtf8 = env->NewStringUTF("UTF-8"); // ASCII なので NewStringUTF で問題ない
  if (localUtf8)
  {
    stringBridge.utf8 = static_cast<jstring>(env->NewGlobalRef(localUtf8));
    env->DeleteLocalRef(localUtf8);
  }
  return stringBridge.stringClass && stringBridge.fromBytes && stringBridge.toBytes && stringBridge.utf8;
}

void releaseStringBridge(JNIEnv *env)
{
  if (stringBridge.stringClass)
  {
    env->DeleteGlobalRef(stringBridge.stringClass);
  }
  if (stringBridge.utf8)
  {
    env->DeleteGlobalRef(stringBridge.utf8);
  }
  stringBridge = StringBridge{};
}

// UTF-8 の C 文字列から Java String を作る。失敗時は nullptr（例外が保留される）
jstring newJavaString(JNIEnv *env, const char *utf8)
{
  if (!utf8)
  {
    return nullptr;
  }
  jsize length = static_cast<jsize>(std::strlen(utf8));
  jbyteArray bytes = env->NewByteArray(length);
  if (!bytes)
  {
    return nullptr;
  }
  env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(utf8));
  jstring result = static_cast<jstring>(env->NewObject(stringBridge.stringClass, stringBridge.fromBytes, bytes, stringBridge.utf8));
  env->DeleteLocalRef(bytes);
  return result;
}

// Java String を UTF-8 の std::string へ写す。null なら空文字列
std::string toUtf8(JNIEnv *env, jstring text)
{
  if (!text)
  {
    return std::string();
  }
  jbyteArray bytes = static_cast<jbyteArray>(env->CallObjectMethod(text, stringBridge.toBytes, stringBridge.utf8));
  if (!bytes)
  {
    return std::string();
  }
  jsize length = env->GetArrayLength(bytes);
  std::string result(static_cast<size_t>(length), '\0');
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
  env->DeleteLocalRef(bytes);
  return result;
}

// whisper_full_params が参照する C 文字列を、whisper_full の呼び出しが終わるまで生かしておく入れ物。
// deque は要素追加で既存要素のアドレスが変わらないので、返した const char * が無効化されない。
class Utf8Strings
{
public:
  explicit Utf8Strings(JNIEnv *env) : env_(env) {}
  Utf8Strings(const Utf8Strings &) = delete;
  Utf8Strings &operator=(const Utf8Strings &) = delete;

  // Java の null は C の nullptr に対応させる（whisper.cpp は nullptr を「未設定」と解釈する）
  const char *hold(jstring text)
  {
    if (!text)
    {
      return nullptr;
    }
    storage_.push_back(toUtf8(env_, text));
    return storage_.back().c_str();
  }

private:
  JNIEnv *env_;
  std::deque<std::string> storage_;
};

// GetFloatArrayElements / ReleaseFloatArrayElements の対を RAII にしたもの
class FloatArrayView
{
public:
  FloatArrayView(JNIEnv *env, jfloatArray array) : env_(env), array_(array)
  {
    if (array_)
    {
      length_ = env_->GetArrayLength(array_);
      data_ = env_->GetFloatArrayElements(array_, nullptr);
    }
  }
  ~FloatArrayView()
  {
    if (data_)
    {
      env_->ReleaseFloatArrayElements(array_, data_, JNI_ABORT); // 読み取り専用なので Java 側へ書き戻さない
    }
  }
  FloatArrayView(const FloatArrayView &) = delete;
  FloatArrayView &operator=(const FloatArrayView &) = delete;

  const float *data() const { return data_; }
  jsize length() const { return length_; }
  bool ok() const { return data_ != nullptr; }

private:
  JNIEnv *env_;
  jfloatArray array_;
  jfloat *data_ = nullptr;
  jsize length_ = 0;
};

// ============================================================================
// 4. フィールド読み出し
// ============================================================================

// Java オブジェクトのフィールドを名前で読む。GetFieldID が失敗（NoSuchFieldError が保留）した時点で
// 以降の JNI 呼び出しを止め、failed() で呼び出し側に知らせる。保留中の例外はそのまま Java へ返る。
class FieldReader
{
public:
  FieldReader(JNIEnv *env, jobject object) : env_(env), object_(object), class_(env->GetObjectClass(object)) {}
  ~FieldReader()
  {
    if (class_)
    {
      env_->DeleteLocalRef(class_);
    }
  }
  FieldReader(const FieldReader &) = delete;
  FieldReader &operator=(const FieldReader &) = delete;

  jint getInt(const char *name, jint fallback)
  {
    jfieldID id = field(name, "I");
    return id ? env_->GetIntField(object_, id) : fallback;
  }

  bool getBool(const char *name, bool fallback)
  {
    jfieldID id = field(name, "Z");
    return id ? env_->GetBooleanField(object_, id) == JNI_TRUE : fallback;
  }

  float getFloat(const char *name, float fallback)
  {
    jfieldID id = field(name, "F");
    return id ? env_->GetFloatField(object_, id) : fallback;
  }

  jstring getString(const char *name)
  {
    return static_cast<jstring>(getObject(name, "Ljava/lang/String;"));
  }

  // 返り値はローカル参照。使い終わったら DeleteLocalRef すること
  jobject getObject(const char *name, const char *signature)
  {
    jfieldID id = field(name, signature);
    return id ? env_->GetObjectField(object_, id) : nullptr;
  }

  bool failed() const { return failed_; }

private:
  jfieldID field(const char *name, const char *signature)
  {
    if (failed_)
    {
      return nullptr;
    }
    jfieldID id = env_->GetFieldID(class_, name, signature);
    if (!id)
    {
      failed_ = true; // NoSuchFieldError が保留された
    }
    return id;
  }

  JNIEnv *env_;
  jobject object_;
  jclass class_;
  bool failed_ = false;
};

// jp.clip.whisperjni.WhisperContextParams -> whisper_context_params
bool readContextParams(JNIEnv *env, jobject jParams, whisper_context_params &params)
{
  params = whisper_context_default_params();
  FieldReader reader(env, jParams);
  params.use_gpu = reader.getBool("useGpu", params.use_gpu);
  return !reader.failed();
}

// jp.clip.whisperjni.WhisperTranscriptionParams$VadParams -> whisper_vad_params
bool readVadParams(JNIEnv *env, jobject jVadParams, whisper_vad_params &vad)
{
  vad = whisper_vad_default_params();
  if (!jVadParams)
  {
    return true;
  }
  FieldReader reader(env, jVadParams);
  vad.threshold = reader.getFloat("threshold", vad.threshold);
  vad.min_speech_duration_ms = reader.getInt("minSpeechDurationMs", vad.min_speech_duration_ms);
  vad.min_silence_duration_ms = reader.getInt("minSilenceDurationMs", vad.min_silence_duration_ms);
  vad.max_speech_duration_s = reader.getFloat("maxSpeechDurationSeconds", vad.max_speech_duration_s);
  vad.speech_pad_ms = reader.getInt("speechPadMs", vad.speech_pad_ms);
  vad.samples_overlap = reader.getFloat("samplesOverlap", vad.samples_overlap);
  return !reader.failed();
}

// jp.clip.whisperjni.WhisperTranscriptionParams -> whisper_full_params
// 文字列は strings に保持され、strings の寿命が params の寿命になる。
bool readTranscriptionParams(JNIEnv *env, jobject jParams, Utf8Strings &strings, whisper_full_params &params)
{
  FieldReader reader(env, jParams);

  auto strategy = static_cast<whisper_sampling_strategy>(reader.getInt("strategy", WHISPER_SAMPLING_GREEDY));
  params = whisper_full_default_params(strategy);

  jint threads = reader.getInt("threads", 0);
  if (threads > 0)
  {
    params.n_threads = threads; // 0 以下なら whisper.cpp の既定値のまま
  }
  params.audio_ctx = reader.getInt("audioContextSize", params.audio_ctx);
  params.n_max_text_ctx = reader.getInt("maxTextContextTokens", params.n_max_text_ctx);
  params.offset_ms = reader.getInt("offsetMs", params.offset_ms);
  params.duration_ms = reader.getInt("durationMs", params.duration_ms);

  params.translate = reader.getBool("translate", params.translate);
  params.no_timestamps = reader.getBool("noTimestamps", params.no_timestamps);
  params.detect_language = reader.getBool("detectLanguage", params.detect_language);
  params.language = strings.hold(reader.getString("language"));
  params.initial_prompt = strings.hold(reader.getString("initialPrompt"));
  params.no_context = reader.getBool("noContext", params.no_context);
  params.single_segment = reader.getBool("singleSegment", params.single_segment);

  params.print_special = reader.getBool("printSpecial", params.print_special);
  params.print_progress = reader.getBool("printProgress", params.print_progress);
  params.print_realtime = reader.getBool("printRealtime", params.print_realtime);
  params.print_timestamps = reader.getBool("printTimestamps", params.print_timestamps);

  params.suppress_blank = reader.getBool("suppressBlank", params.suppress_blank);
  params.suppress_nst = reader.getBool("suppressNonSpeechTokens", params.suppress_nst);
  params.temperature = reader.getFloat("temperature", params.temperature);
  params.max_initial_ts = reader.getFloat("maxInitialTimestampSeconds", params.max_initial_ts);
  params.length_penalty = reader.getFloat("lengthPenalty", params.length_penalty);
  params.temperature_inc = reader.getFloat("temperatureIncrement", params.temperature_inc);
  params.entropy_thold = reader.getFloat("entropyThreshold", params.entropy_thold);
  params.logprob_thold = reader.getFloat("logProbabilityThreshold", params.logprob_thold);
  params.no_speech_thold = reader.getFloat("noSpeechThreshold", params.no_speech_thold);

  switch (strategy)
  {
  case WHISPER_SAMPLING_GREEDY:
    params.greedy.best_of = reader.getInt("greedyBestOf", params.greedy.best_of);
    break;
  case WHISPER_SAMPLING_BEAM_SEARCH:
    params.beam_search.beam_size = reader.getInt("beamSize", params.beam_search.beam_size);
    params.beam_search.patience = reader.getFloat("beamPatience", params.beam_search.patience);
    break;
  }

  params.vad = reader.getBool("vadEnabled", params.vad);
  params.vad_model_path = strings.hold(reader.getString("vadModelPath"));

  jobject jVadParams = reader.getObject("vadParams", "Ljp/clip/whisperjni/WhisperTranscriptionParams$VadParams;");
  bool vadOk = readVadParams(env, jVadParams, params.vad_params);
  if (jVadParams)
  {
    env->DeleteLocalRef(jVadParams);
  }

  return vadOk && !reader.failed();
}

// ============================================================================
// 5. 文法
// ============================================================================

// params.grammar_rules が指す配列。whisper_full が終わるまで生かしておく必要がある
using GrammarRules = std::vector<const whisper_grammar_element *>;

// WhisperTranscriptionParams.grammar が設定されていれば params に文法を適用する。
// 戻り値 false は Java 例外が保留されたことを意味する。
bool applyGrammar(JNIEnv *env, jobject jParams, whisper_full_params &params, GrammarRules &rules)
{
  FieldReader paramsReader(env, jParams);
  jobject jGrammar = paramsReader.getObject("grammar", "Ljp/clip/whisperjni/WhisperGrammar;");
  float penalty = paramsReader.getFloat("grammarPenalty", params.grammar_penalty);
  if (paramsReader.failed())
  {
    return false;
  }
  if (!jGrammar)
  {
    return true; // 文法なし
  }

  jint grammarRef;
  {
    FieldReader grammarReader(env, jGrammar); // nativeId は基底クラス NativeHandle のフィールド
    grammarRef = grammarReader.getInt("nativeId", -1);
    env->DeleteLocalRef(jGrammar);
    if (grammarReader.failed())
    {
      return false;
    }
  }

  grammar_parser::parse_state *grammar = grammars.find(grammarRef);
  if (!grammar)
  {
    throwJava(env, "java/lang/IllegalStateException", "Unknown whisper grammar handle: " + std::to_string(grammarRef));
    return false;
  }

  auto root = grammar->symbol_ids.find("root");
  if (grammar->rules.empty() || root == grammar->symbol_ids.end())
  {
    return true; // loadGrammar で弾いているので通常ここには来ない
  }

  rules = grammar->c_rules();
  params.grammar_rules = rules.data();
  params.n_grammar_rules = rules.size();
  params.i_start_rule = root->second;
  params.grammar_penalty = penalty;
  return true;
}

// ============================================================================
// 6. WhisperToken
// ============================================================================

// jp.clip.whisperjni.WhisperToken のクラスとコンストラクタ。最初に必要になったときに解決して保持する
struct WhisperTokenBridge
{
  std::mutex mutex;
  jclass tokenClass = nullptr;
  jmethodID constructor = nullptr; // (Ljava/lang/String;IIFFFFJJJF)V — Java 側のコンストラクタと一致させること
} whisperTokenBridge;

bool ensureWhisperTokenBridge(JNIEnv *env)
{
  std::lock_guard<std::mutex> lock(whisperTokenBridge.mutex);
  if (whisperTokenBridge.tokenClass)
  {
    return true;
  }
  jclass localClass = env->FindClass("jp/clip/whisperjni/WhisperToken"); // パッケージ名を変えたらここも変える
  if (!localClass)
  {
    return false;
  }
  jmethodID constructor = env->GetMethodID(localClass, "<init>", "(Ljava/lang/String;IIFFFFJJJF)V");
  if (constructor)
  {
    whisperTokenBridge.tokenClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    whisperTokenBridge.constructor = constructor;
  }
  env->DeleteLocalRef(localClass);
  return whisperTokenBridge.tokenClass != nullptr;
}

void releaseWhisperTokenBridge(JNIEnv *env)
{
  std::lock_guard<std::mutex> lock(whisperTokenBridge.mutex);
  if (whisperTokenBridge.tokenClass)
  {
    env->DeleteGlobalRef(whisperTokenBridge.tokenClass);
    whisperTokenBridge.tokenClass = nullptr;
    whisperTokenBridge.constructor = nullptr;
  }
}

jobject newWhisperToken(JNIEnv *env, const char *text, const whisper_token_data &token)
{
  jstring jText = newJavaString(env, text ? text : "");
  if (!jText)
  {
    return nullptr;
  }
  jobject result = env->NewObject(whisperTokenBridge.tokenClass, whisperTokenBridge.constructor,
                                  jText,
                                  static_cast<jint>(token.id),
                                  static_cast<jint>(token.tid),
                                  static_cast<jfloat>(token.p),
                                  static_cast<jfloat>(token.plog),
                                  static_cast<jfloat>(token.pt),
                                  static_cast<jfloat>(token.ptsum),
                                  static_cast<jlong>(token.t0),
                                  static_cast<jlong>(token.t1),
                                  static_cast<jlong>(token.t_dtw),
                                  static_cast<jfloat>(token.vlen));
  env->DeleteLocalRef(jText);
  return result;
}

// tokenCount 個のトークンを WhisperToken[] にまとめる。
// dataAt(i) は whisper_token_data、textAt(i) は const char * を返す呼び出し可能オブジェクト。
template <typename DataAt, typename TextAt>
jobjectArray newWhisperTokenArray(JNIEnv *env, int tokenCount, DataAt dataAt, TextAt textAt)
{
  if (!ensureWhisperTokenBridge(env))
  {
    return nullptr;
  }
  jobjectArray array = env->NewObjectArray(tokenCount, whisperTokenBridge.tokenClass, nullptr);
  if (!array)
  {
    return nullptr;
  }
  for (int i = 0; i < tokenCount; i++)
  {
    jobject token = newWhisperToken(env, textAt(i), dataAt(i));
    if (!token)
    {
      return nullptr; // 例外が保留されている
    }
    env->SetObjectArrayElement(array, i, token);
    env->DeleteLocalRef(token);
  }
  return array;
}

// ============================================================================
// 7. ログ
// ============================================================================

// setLogger で受け取った SLF4J Logger と、そのメソッド ID
struct LoggerBridge
{
  std::mutex mutex;
  JavaVM *vm = nullptr;
  jobject logger = nullptr; // グローバル参照
  jmethodID error = nullptr;
  jmethodID warn = nullptr;
  jmethodID info = nullptr;
  jmethodID debug = nullptr;
} loggerBridge;

jmethodID loggerMethodFor(const LoggerBridge &bridge, ggml_log_level level)
{
  switch (level)
  {
  case GGML_LOG_LEVEL_ERROR:
    return bridge.error;
  case GGML_LOG_LEVEL_WARN:
    return bridge.warn;
  case GGML_LOG_LEVEL_DEBUG:
    return bridge.debug;
  case GGML_LOG_LEVEL_INFO:
  case GGML_LOG_LEVEL_CONT: // 前行の続き。行単位で来るので INFO 扱いにする
  case GGML_LOG_LEVEL_NONE:
  default:
    return bridge.info;
  }
}

std::string stripTrailingWhitespace(const char *text)
{
  std::string message(text);
  while (!message.empty() && std::isspace(static_cast<unsigned char>(message.back())))
  {
    message.pop_back();
  }
  return message;
}

// whisper_log_set に登録するコールバック。whisper.cpp の作業スレッドから呼ばれることもある
void forwardLogToJava(ggml_log_level level, const char *text, void * /*userData*/)
{
  if (!text)
  {
    return;
  }
  std::string message = stripTrailingWhitespace(text); // whisper.cpp のログは末尾に改行が付いている
  if (message.empty())
  {
    return;
  }

  JavaVM *vm;
  jobject logger;
  jmethodID method;
  {
    std::lock_guard<std::mutex> lock(loggerBridge.mutex);
    vm = loggerBridge.vm;
    logger = loggerBridge.logger;
    method = loggerMethodFor(loggerBridge, level);
  }
  if (!vm || !logger || !method)
  {
    return;
  }

  // 呼び出し元が Java スレッドなら GetEnv で足りる。ネイティブ専用スレッドだけをアタッチし、
  // 自分でアタッチした場合だけデタッチする（Java スレッドをデタッチしてはいけない）。
  JNIEnv *env = nullptr;
  jint status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  bool attachedHere = false;
  if (status == JNI_EDETACHED)
  {
    if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK)
    {
      return;
    }
    attachedHere = true;
  }
  else if (status != JNI_OK)
  {
    return;
  }

  jstring jMessage = newJavaString(env, message.c_str());
  if (jMessage)
  {
    env->CallVoidMethod(logger, method, jMessage);
    env->DeleteLocalRef(jMessage);
  }
  if (env->ExceptionCheck())
  {
    env->ExceptionClear(); // ロガー側の例外をネイティブ処理の途中に持ち越さない
  }

  if (attachedHere)
  {
    vm->DetachCurrentThread();
  }
}

// ============================================================================
// 8. JNI エクスポートの共通処理
// ============================================================================

// ファイルパスからコンテキストを生成する（whisper_init_from_file_with_params[_no_state]）
jint initContextFromFile(JNIEnv *env, jstring jModelPath, jobject jParams, bool withState)
{
  whisper_context_params params;
  if (!readContextParams(env, jParams, params))
  {
    return -1;
  }
  std::string modelPath = toUtf8(env, jModelPath);
  whisper_context *context = withState
                                 ? whisper_init_from_file_with_params(modelPath.c_str(), params)
                                 : whisper_init_from_file_with_params_no_state(modelPath.c_str(), params);
  return context ? contexts.add(context) : -1;
}

// InputStream の内容をすべてメモリへ読み込んだもの。whisper_model_loader の context として使う
struct ModelBuffer
{
  std::vector<uint8_t> data;
  size_t offset = 0;

  size_t read(void *output, size_t size)
  {
    size_t available = data.size() - offset;
    size_t count = std::min(size, available);
    if (count > 0)
    {
      std::memcpy(output, data.data() + offset, count);
      offset += count;
    }
    return count;
  }

  bool eof() const { return offset >= data.size(); }
};

// java.io.InputStream を末尾まで読み切る。失敗時は false（IOException などが保留される）
bool readAllBytes(JNIEnv *env, jobject jInputStream, std::vector<uint8_t> &out)
{
  jclass inputStreamClass = env->FindClass("java/io/InputStream");
  if (!inputStreamClass)
  {
    return false;
  }
  jmethodID readMethod = env->GetMethodID(inputStreamClass, "read", "([B)I");
  env->DeleteLocalRef(inputStreamClass);
  if (!readMethod)
  {
    return false;
  }

  const jsize chunkSize = 64 * 1024;
  jbyteArray chunk = env->NewByteArray(chunkSize);
  if (!chunk)
  {
    return false;
  }

  bool ok = true;
  while (true)
  {
    jint bytesRead = env->CallIntMethod(jInputStream, readMethod, chunk);
    if (env->ExceptionCheck())
    {
      ok = false; // read() が投げた IOException をそのまま Java へ返す
      break;
    }
    if (bytesRead < 0)
    {
      break; // ストリーム終端
    }
    size_t previousSize = out.size();
    out.resize(previousSize + static_cast<size_t>(bytesRead));
    env->GetByteArrayRegion(chunk, 0, bytesRead, reinterpret_cast<jbyte *>(out.data() + previousSize));
  }
  env->DeleteLocalRef(chunk);
  return ok;
}

// whisper_full / whisper_full_with_state の共通部分。state が nullptr なら whisper_full
jint runFull(JNIEnv *env, whisper_context *context, whisper_state *state, jobject jParams, jfloatArray jSamples, jint numSamples)
{
  Utf8Strings strings(env);
  whisper_full_params params;
  if (!readTranscriptionParams(env, jParams, strings, params))
  {
    return -1;
  }
  GrammarRules grammarRules;
  if (!applyGrammar(env, jParams, params, grammarRules))
  {
    return -1;
  }

  FloatArrayView samples(env, jSamples);
  if (!samples.ok())
  {
    throwJava(env, "java/lang/IllegalArgumentException", "samples must not be null");
    return -1;
  }
  if (numSamples < 0 || numSamples > samples.length())
  {
    throwJava(env, "java/lang/IllegalArgumentException",
              "numSamples (" + std::to_string(numSamples) + ") exceeds samples.length (" + std::to_string(samples.length()) + ")");
    return -1;
  }

  return state
             ? whisper_full_with_state(context, state, params, samples.data(), numSamples)
             : whisper_full(context, params, samples.data(), numSamples);
}

// セグメント系アクセサの共通形。Owner は whisper_context か whisper_state
template <typename Owner>
jlong segmentTimestamp(JNIEnv *env, Owner *owner, jint segment, int (*count)(Owner *), int64_t (*get)(Owner *, int))
{
  if (!owner || !checkIndex(env, count(owner), segment))
  {
    return 0;
  }
  return static_cast<jlong>(get(owner, segment));
}

template <typename Owner>
jstring segmentText(JNIEnv *env, Owner *owner, jint segment, int (*count)(Owner *), const char *(*get)(Owner *, int))
{
  if (!owner || !checkIndex(env, count(owner), segment))
  {
    return nullptr;
  }
  return newJavaString(env, get(owner, segment));
}

} // namespace

// ============================================================================
// JNI ライフサイクル
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
{
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
  {
    return JNI_ERR;
  }
  if (!initStringBridge(env))
  {
    return JNI_ERR;
  }
  ggml_backend_load_all(); // 動的にビルドされた ggml バックエンド（CUDA / Vulkan など）を探して読み込む
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void * /*reserved*/)
{
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
  {
    return;
  }
  whisper_log_set(nullptr, nullptr);
  {
    std::lock_guard<std::mutex> lock(loggerBridge.mutex);
    if (loggerBridge.logger)
    {
      env->DeleteGlobalRef(loggerBridge.logger);
      loggerBridge.logger = nullptr;
    }
    loggerBridge.vm = nullptr;
  }
  releaseWhisperTokenBridge(env);
  releaseStringBridge(env);
}

// ============================================================================
// 生成
// ============================================================================

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_init(JNIEnv *env, jobject, jstring jModelPath, jobject jParams)
{
  return initContextFromFile(env, jModelPath, jParams, true);
}

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_initNoState(JNIEnv *env, jobject, jstring jModelPath, jobject jParams)
{
  return initContextFromFile(env, jModelPath, jParams, false);
}

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_initFromInputStream(JNIEnv *env, jobject, jobject jInputStream, jobject jParams, jboolean initState)
{
  whisper_context_params params;
  if (!readContextParams(env, jParams, params))
  {
    return -1;
  }

  ModelBuffer buffer;
  if (!readAllBytes(env, jInputStream, buffer.data) || buffer.data.empty())
  {
    return -1;
  }

  whisper_model_loader loader = {};
  loader.context = &buffer;
  loader.read = [](void *ctx, void *output, size_t size) { return static_cast<ModelBuffer *>(ctx)->read(output, size); };
  loader.eof = [](void *ctx) { return static_cast<ModelBuffer *>(ctx)->eof(); };
  loader.close = [](void *) { /* buffer はこの関数の終わりで解放される */ };

  whisper_context *context = initState == JNI_TRUE
                                 ? whisper_init_with_params(&loader, params)
                                 : whisper_init_with_params_no_state(&loader, params);
  return context ? contexts.add(context) : -1;
}

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_initState(JNIEnv *env, jobject, jint contextRef)
{
  whisper_context *context = requireContext(env, contextRef);
  if (!context)
  {
    return -1;
  }
  whisper_state *state = whisper_init_state(context);
  return state ? states.add(state) : -1;
}

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_loadGrammar(JNIEnv *env, jobject, jstring jGrammarText)
{
  std::string grammarText = toUtf8(env, jGrammarText);

  // grammar_parser::parse は解析エラーを内部で握りつぶして空の parse_state を返す（stderr にだけ出す）。
  // ここで空や root 無しを検出し、Java 側へ IOException として伝える。
  grammar_parser::parse_state parsed = grammar_parser::parse(grammarText.c_str());
  if (parsed.rules.empty() || parsed.symbol_ids.find("root") == parsed.symbol_ids.end())
  {
    throwJava(env, "java/io/IOException", "Failed to parse GBNF grammar (see stderr for details); it must define a 'root' rule");
    return -1;
  }
  return grammars.add(new grammar_parser::parse_state(std::move(parsed)));
}

// ============================================================================
// 照会
// ============================================================================

JNIEXPORT void JNICALL Java_jp_clip_whisperjni_WhisperJNI_initOpenVINOEncoder(JNIEnv *env, jobject, jint contextRef, jstring jDevice)
{
  whisper_context *context = requireContext(env, contextRef);
  if (!context)
  {
    return;
  }
  std::string device = toUtf8(env, jDevice);
  // OpenVINO 無しでビルドされている場合は whisper.cpp 側が警告を出して何もしない
  whisper_ctx_init_openvino_encoder(context, nullptr, device.c_str(), nullptr);
}

JNIEXPORT jboolean JNICALL Java_jp_clip_whisperjni_WhisperJNI_isMultilingual(JNIEnv *env, jobject, jint contextRef)
{
  whisper_context *context = requireContext(env, contextRef);
  return context && whisper_is_multilingual(context) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_jp_clip_whisperjni_WhisperJNI_printSystemInfo(JNIEnv *env, jobject)
{
  return newJavaString(env, whisper_print_system_info());
}

// ============================================================================
// 文字起こし
// ============================================================================

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_full(JNIEnv *env, jobject, jint contextRef, jobject jParams, jfloatArray jSamples, jint numSamples)
{
  whisper_context *context = requireContext(env, contextRef);
  if (!context)
  {
    return -1;
  }
  return runFull(env, context, nullptr, jParams, jSamples, numSamples);
}

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullWithState(JNIEnv *env, jobject, jint contextRef, jint stateRef, jobject jParams, jfloatArray jSamples, jint numSamples)
{
  whisper_context *context = requireContext(env, contextRef);
  whisper_state *state = context ? requireState(env, stateRef) : nullptr;
  if (!context || !state)
  {
    return -1;
  }
  return runFull(env, context, state, jParams, jSamples, numSamples);
}

// ============================================================================
// 結果（context 版）
// ============================================================================

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullNSegments(JNIEnv *env, jobject, jint contextRef)
{
  whisper_context *context = requireContext(env, contextRef);
  return context ? whisper_full_n_segments(context) : 0;
}

JNIEXPORT jlong JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullGetSegmentTimestamp0(JNIEnv *env, jobject, jint contextRef, jint segment)
{
  return segmentTimestamp(env, requireContext(env, contextRef), segment, whisper_full_n_segments, whisper_full_get_segment_t0);
}

JNIEXPORT jlong JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullGetSegmentTimestamp1(JNIEnv *env, jobject, jint contextRef, jint segment)
{
  return segmentTimestamp(env, requireContext(env, contextRef), segment, whisper_full_n_segments, whisper_full_get_segment_t1);
}

JNIEXPORT jstring JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullGetSegmentText(JNIEnv *env, jobject, jint contextRef, jint segment)
{
  return segmentText(env, requireContext(env, contextRef), segment, whisper_full_n_segments, whisper_full_get_segment_text);
}

JNIEXPORT jobjectArray JNICALL Java_jp_clip_whisperjni_WhisperJNI_getSegmentTokens(JNIEnv *env, jobject, jint contextRef, jint segment)
{
  whisper_context *context = requireContext(env, contextRef);
  if (!context || !checkIndex(env, whisper_full_n_segments(context), segment))
  {
    return nullptr;
  }
  int tokenCount = whisper_full_n_tokens(context, segment);
  return newWhisperTokenArray(
      env, tokenCount,
      [&](int i) { return whisper_full_get_token_data(context, segment, i); },
      [&](int i) { return whisper_full_get_token_text(context, segment, i); });
}

// ============================================================================
// 結果（state 版）
// ============================================================================

JNIEXPORT jint JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullNSegmentsFromState(JNIEnv *env, jobject, jint stateRef)
{
  whisper_state *state = requireState(env, stateRef);
  return state ? whisper_full_n_segments_from_state(state) : 0;
}

JNIEXPORT jlong JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullGetSegmentTimestamp0FromState(JNIEnv *env, jobject, jint stateRef, jint segment)
{
  return segmentTimestamp(env, requireState(env, stateRef), segment, whisper_full_n_segments_from_state, whisper_full_get_segment_t0_from_state);
}

JNIEXPORT jlong JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullGetSegmentTimestamp1FromState(JNIEnv *env, jobject, jint stateRef, jint segment)
{
  return segmentTimestamp(env, requireState(env, stateRef), segment, whisper_full_n_segments_from_state, whisper_full_get_segment_t1_from_state);
}

JNIEXPORT jstring JNICALL Java_jp_clip_whisperjni_WhisperJNI_fullGetSegmentTextFromState(JNIEnv *env, jobject, jint stateRef, jint segment)
{
  return segmentText(env, requireState(env, stateRef), segment, whisper_full_n_segments_from_state, whisper_full_get_segment_text_from_state);
}

JNIEXPORT jobjectArray JNICALL Java_jp_clip_whisperjni_WhisperJNI_getSegmentTokensFromState(JNIEnv *env, jobject, jint contextRef, jint stateRef, jint segment)
{
  whisper_context *context = requireContext(env, contextRef);
  whisper_state *state = context ? requireState(env, stateRef) : nullptr;
  if (!context || !state || !checkIndex(env, whisper_full_n_segments_from_state(state), segment))
  {
    return nullptr;
  }
  int tokenCount = whisper_full_n_tokens_from_state(state, segment);
  return newWhisperTokenArray(
      env, tokenCount,
      [&](int i) { return whisper_full_get_token_data_from_state(state, segment, i); },
      [&](int i) { return whisper_full_get_token_text_from_state(context, state, segment, i); }); // テキストだけは context も要る
}

// ============================================================================
// 解放
// ============================================================================

JNIEXPORT void JNICALL Java_jp_clip_whisperjni_WhisperJNI_freeContext(JNIEnv *, jobject, jint contextRef)
{
  if (whisper_context *context = contexts.remove(contextRef))
  {
    whisper_free(context);
  }
}

JNIEXPORT void JNICALL Java_jp_clip_whisperjni_WhisperJNI_freeState(JNIEnv *, jobject, jint stateRef)
{
  if (whisper_state *state = states.remove(stateRef))
  {
    whisper_free_state(state);
  }
}

JNIEXPORT void JNICALL Java_jp_clip_whisperjni_WhisperJNI_freeGrammar(JNIEnv *, jobject, jint grammarRef)
{
  // new で確保しているので delete で解放する（free() ではデストラクタが走らない）
  delete grammars.remove(grammarRef);
}

// ============================================================================
// ログ
// ============================================================================

JNIEXPORT void JNICALL Java_jp_clip_whisperjni_WhisperJNI_setLogger(JNIEnv *env, jclass, jobject jLogger)
{
  JavaVM *vm = nullptr;
  if (env->GetJavaVM(&vm) != JNI_OK)
  {
    throwJava(env, "java/lang/IllegalStateException", "Failed to obtain the JavaVM reference");
    return;
  }

  jmethodID error = nullptr, warn = nullptr, info = nullptr, debug = nullptr;
  jobject globalLogger = nullptr;
  if (jLogger)
  {
    // Logger インタフェースではなく実際のクラスから解決する（org/slf4j/Logger を FindClass しなくて済む）
    jclass loggerClass = env->GetObjectClass(jLogger);
    error = env->GetMethodID(loggerClass, "error", "(Ljava/lang/String;)V");
    warn = env->GetMethodID(loggerClass, "warn", "(Ljava/lang/String;)V");
    info = env->GetMethodID(loggerClass, "info", "(Ljava/lang/String;)V");
    debug = env->GetMethodID(loggerClass, "debug", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(loggerClass);
    if (!error || !warn || !info || !debug)
    {
      return; // NoSuchMethodError が保留されている
    }
    globalLogger = env->NewGlobalRef(jLogger);
  }

  jobject previousLogger;
  {
    std::lock_guard<std::mutex> lock(loggerBridge.mutex);
    previousLogger = loggerBridge.logger;
    loggerBridge.vm = vm;
    loggerBridge.logger = globalLogger;
    loggerBridge.error = error;
    loggerBridge.warn = warn;
    loggerBridge.info = info;
    loggerBridge.debug = debug;
  }
  if (previousLogger)
  {
    env->DeleteGlobalRef(previousLogger);
  }

  whisper_log_set(globalLogger ? forwardLogToJava : nullptr, nullptr);
}
