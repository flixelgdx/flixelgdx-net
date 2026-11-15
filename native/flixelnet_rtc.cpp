/*
 * MIT License
 *
 * Copyright (c) 2026 stringdotjar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/*
 * The native half of org.flixelgdx.net.jvm.FlixelJvmRtc: a thin JNI layer over libdatachannel's
 * C API that gives Java WebRTC data channels.
 *
 * libdatachannel reports everything (a new candidate, a message, a channel opening) by calling
 * back on its own threads. Calling into Java from those threads would mean attaching each of
 * them to the Java virtual machine and would run game code off the game thread. Instead, every
 * callback only writes a small record into a queue here, and Java empties that queue from the
 * game thread with nPoll(). Nothing in this file ever calls into Java.
 *
 * A "handle" is what Java holds for one peer connection. It is a slot number plus a counter that
 * changes every time the slot is reused, so a late callback for a connection that was already
 * closed can never be mistaken for the slot's next user.
 */

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include <rtc/rtc.h>

namespace {

constexpr int kMaxSlots = 256;
constexpr int kReliable = 0;
constexpr int kUnreliable = 1;

// Event types, matching the constants in FlixelJvmRtc.java.
constexpr int kEventDescription = 1;
constexpr int kEventCandidate = 2;
constexpr int kEventState = 3;
constexpr int kEventOpen = 4;
constexpr int kEventClosed = 5;
constexpr int kEventReliable = 6;
constexpr int kEventUnreliable = 7;

// If Java stops polling, messages are thrown away past this many queued bytes instead of piling
// up without limit.
constexpr size_t kMaxQueuedBytes = 32u * 1024u * 1024u;

struct Event {
  int type;
  int handle;
  std::vector<char> data;
};

struct Slot {
  bool used = false;
  int generation = 0;
  int pc = -1;
  int channel[2] = {-1, -1};
};

std::mutex gLock;
std::deque<Event> gEvents;
size_t gQueuedBytes = 0;
Slot gSlots[kMaxSlots];

int handleOf(int index) { return index | (gSlots[index].generation << 8); }

void *pointerOf(int handle) { return reinterpret_cast<void *>(static_cast<intptr_t>(handle) + 1); }

int handleFrom(void *ptr) { return static_cast<int>(reinterpret_cast<intptr_t>(ptr) - 1); }

// Returns the slot a handle refers to, or null if that connection is gone. The lock must be held.
Slot *slotOf(int handle) {
  int index = handle & 0xFF;
  if (handle < 0 || index >= kMaxSlots) {
    return nullptr;
  }
  Slot *slot = &gSlots[index];
  return slot->used && slot->generation == (handle >> 8) ? slot : nullptr;
}

void push(int type, int handle, const char *data, size_t size, bool droppable) {
  std::lock_guard<std::mutex> lock(gLock);
  if (slotOf(handle) == nullptr) {
    return;
  }
  if (droppable && gQueuedBytes + size > kMaxQueuedBytes) {
    return;
  }
  Event event;
  event.type = type;
  event.handle = handle;
  event.data.assign(data, data + size);
  gQueuedBytes += size;
  gEvents.push_back(std::move(event));
}

void pushText(int type, int handle, const char *first, const char *second) {
  std::string text(first ? first : "");
  text.push_back('\n');
  text.append(second ? second : "");
  push(type, handle, text.data(), text.size(), false);
}

// Returns which of a connection's two channels a data channel id is, or -1.
int channelOf(int handle, int dc) {
  std::lock_guard<std::mutex> lock(gLock);
  Slot *slot = slotOf(handle);
  if (slot == nullptr) {
    return -1;
  }
  if (slot->channel[kReliable] == dc) {
    return kReliable;
  }
  return slot->channel[kUnreliable] == dc ? kUnreliable : -1;
}

void RTC_API onDescription(int, const char *sdp, const char *type, void *ptr) {
  pushText(kEventDescription, handleFrom(ptr), type, sdp);
}

void RTC_API onCandidate(int, const char *candidate, const char *mid, void *ptr) {
  pushText(kEventCandidate, handleFrom(ptr), mid, candidate);
}

void RTC_API onState(int, rtcState state, void *ptr) {
  char value = static_cast<char>(state);
  push(kEventState, handleFrom(ptr), &value, 1, false);
}

void RTC_API onOpen(int dc, void *ptr) {
  int handle = handleFrom(ptr);
  int channel = channelOf(handle, dc);
  if (channel >= 0) {
    char value = static_cast<char>(channel);
    push(kEventOpen, handle, &value, 1, false);
  }
}

void RTC_API onClosed(int dc, void *ptr) {
  int handle = handleFrom(ptr);
  int channel = channelOf(handle, dc);
  if (channel >= 0) {
    char value = static_cast<char>(channel);
    push(kEventClosed, handle, &value, 1, false);
  }
}

void RTC_API onMessage(int dc, const char *message, int size, void *ptr) {
  // A negative size means text. The extension only ever sends binary messages.
  if (size < 0) {
    return;
  }
  int handle = handleFrom(ptr);
  int channel = channelOf(handle, dc);
  if (channel >= 0) {
    push(channel == kReliable ? kEventReliable : kEventUnreliable, handle, message, static_cast<size_t>(size), true);
  }
}

void watchChannel(int dc, int handle) {
  rtcSetUserPointer(dc, pointerOf(handle));
  rtcSetOpenCallback(dc, onOpen);
  rtcSetClosedCallback(dc, onClosed);
  rtcSetMessageCallback(dc, onMessage);
}

// Called when the other side created a data channel. Its label says which of the two it is.
void RTC_API onDataChannel(int, int dc, void *ptr) {
  int handle = handleFrom(ptr);
  char label[16] = {0};
  if (rtcGetDataChannelLabel(dc, label, sizeof(label)) < 0) {
    return;
  }
  int channel = std::strcmp(label, "r") == 0 ? kReliable : std::strcmp(label, "u") == 0 ? kUnreliable : -1;
  if (channel < 0) {
    return;
  }
  {
    std::lock_guard<std::mutex> lock(gLock);
    Slot *slot = slotOf(handle);
    if (slot == nullptr || slot->channel[channel] >= 0) {
      return;
    }
    slot->channel[channel] = dc;
  }
  watchChannel(dc, handle);
  // A channel that arrives this way may already be open, in which case no open callback follows.
  if (rtcIsOpen(dc)) {
    char value = static_cast<char>(channel);
    push(kEventOpen, handle, &value, 1, false);
  }
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nCreate(JNIEnv *env, jclass, jobjectArray servers,
                                                                        jint port, jboolean offer) {
  int index = -1;
  int handle = -1;
  {
    std::lock_guard<std::mutex> lock(gLock);
    for (int i = 0; i < kMaxSlots; i++) {
      if (!gSlots[i].used) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      return -1;
    }
    Slot &slot = gSlots[index];
    slot.used = true;
    slot.generation = (slot.generation + 1) & 0x3FFFFF;
    slot.pc = -1;
    slot.channel[kReliable] = -1;
    slot.channel[kUnreliable] = -1;
    handle = handleOf(index);
  }

  jsize count = servers != nullptr ? env->GetArrayLength(servers) : 0;
  std::vector<std::string> urls;
  for (jsize i = 0; i < count; i++) {
    jstring text = static_cast<jstring>(env->GetObjectArrayElement(servers, i));
    if (text != nullptr) {
      const char *chars = env->GetStringUTFChars(text, nullptr);
      if (chars != nullptr) {
        urls.emplace_back(chars);
        env->ReleaseStringUTFChars(text, chars);
      }
      env->DeleteLocalRef(text);
    }
  }
  std::vector<const char *> pointers;
  for (const std::string &url : urls) {
    pointers.push_back(url.c_str());
  }

  rtcConfiguration config;
  std::memset(&config, 0, sizeof(config));
  config.iceServers = pointers.empty() ? nullptr : pointers.data();
  config.iceServersCount = static_cast<int>(pointers.size());
  if (port > 0) {
    // One fixed port shared by every connection, so a host only has to open a single port.
    config.portRangeBegin = static_cast<uint16_t>(port);
    config.portRangeEnd = static_cast<uint16_t>(port);
    config.enableIceUdpMux = true;
  }

  int pc = rtcCreatePeerConnection(&config);
  if (pc < 0) {
    std::lock_guard<std::mutex> lock(gLock);
    gSlots[index].used = false;
    return -1;
  }
  {
    std::lock_guard<std::mutex> lock(gLock);
    gSlots[index].pc = pc;
  }
  rtcSetUserPointer(pc, pointerOf(handle));
  rtcSetLocalDescriptionCallback(pc, onDescription);
  rtcSetLocalCandidateCallback(pc, onCandidate);
  rtcSetStateChangeCallback(pc, onState);
  rtcSetDataChannelCallback(pc, onDataChannel);

  if (offer) {
    // The side that makes the offer also makes the two channels: one that never loses anything,
    // and one that never waits or resends. Creating them starts the offer by itself.
    rtcDataChannelInit reliable;
    std::memset(&reliable, 0, sizeof(reliable));
    rtcDataChannelInit unreliable;
    std::memset(&unreliable, 0, sizeof(unreliable));
    unreliable.reliability.unordered = true;
    unreliable.reliability.unreliable = true;
    unreliable.reliability.maxRetransmits = 0;
    int r = rtcCreateDataChannelEx(pc, "r", &reliable);
    int u = rtcCreateDataChannelEx(pc, "u", &unreliable);
    if (r < 0 || u < 0) {
      rtcDeletePeerConnection(pc);
      std::lock_guard<std::mutex> lock(gLock);
      gSlots[index].used = false;
      return -1;
    }
    {
      std::lock_guard<std::mutex> lock(gLock);
      gSlots[index].channel[kReliable] = r;
      gSlots[index].channel[kUnreliable] = u;
    }
    watchChannel(r, handle);
    watchChannel(u, handle);
  }
  return handle;
}

JNIEXPORT jint JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nSetRemoteDescription(JNIEnv *env, jclass, jint handle,
                                                                                      jstring sdp, jstring type) {
  int pc = -1;
  {
    std::lock_guard<std::mutex> lock(gLock);
    Slot *slot = slotOf(handle);
    if (slot != nullptr) {
      pc = slot->pc;
    }
  }
  if (pc < 0 || sdp == nullptr || type == nullptr) {
    return -1;
  }
  const char *sdpChars = env->GetStringUTFChars(sdp, nullptr);
  const char *typeChars = env->GetStringUTFChars(type, nullptr);
  int result = sdpChars != nullptr && typeChars != nullptr ? rtcSetRemoteDescription(pc, sdpChars, typeChars) : -1;
  if (sdpChars != nullptr) {
    env->ReleaseStringUTFChars(sdp, sdpChars);
  }
  if (typeChars != nullptr) {
    env->ReleaseStringUTFChars(type, typeChars);
  }
  return result;
}

JNIEXPORT jint JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nAddRemoteCandidate(JNIEnv *env, jclass, jint handle,
                                                                                    jstring candidate, jstring mid) {
  int pc = -1;
  {
    std::lock_guard<std::mutex> lock(gLock);
    Slot *slot = slotOf(handle);
    if (slot != nullptr) {
      pc = slot->pc;
    }
  }
  if (pc < 0 || candidate == nullptr || mid == nullptr) {
    return -1;
  }
  const char *candidateChars = env->GetStringUTFChars(candidate, nullptr);
  const char *midChars = env->GetStringUTFChars(mid, nullptr);
  int result = candidateChars != nullptr && midChars != nullptr
                   ? rtcAddRemoteCandidate(pc, candidateChars, midChars)
                   : -1;
  if (candidateChars != nullptr) {
    env->ReleaseStringUTFChars(candidate, candidateChars);
  }
  if (midChars != nullptr) {
    env->ReleaseStringUTFChars(mid, midChars);
  }
  return result;
}

JNIEXPORT jint JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nSend(JNIEnv *env, jclass, jint handle, jint channel,
                                                                      jbyteArray data, jint offset, jint length) {
  int dc = -1;
  {
    std::lock_guard<std::mutex> lock(gLock);
    Slot *slot = slotOf(handle);
    if (slot != nullptr && (channel == kReliable || channel == kUnreliable)) {
      dc = slot->channel[channel];
    }
  }
  if (dc < 0 || length < 0) {
    return -1;
  }
  // One buffer per thread, grown as needed and then reused, so sending does not allocate.
  static thread_local std::vector<char> scratch;
  if (scratch.size() < static_cast<size_t>(length) + 1) {
    scratch.resize(static_cast<size_t>(length) + 1);
  }
  env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte *>(scratch.data()));
  if (env->ExceptionCheck()) {
    return -1;
  }
  return rtcSendMessage(dc, scratch.data(), length);
}

JNIEXPORT jint JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nBuffered(JNIEnv *, jclass, jint handle, jint channel) {
  int dc = -1;
  {
    std::lock_guard<std::mutex> lock(gLock);
    Slot *slot = slotOf(handle);
    if (slot != nullptr && (channel == kReliable || channel == kUnreliable)) {
      dc = slot->channel[channel];
    }
  }
  return dc < 0 ? 0 : rtcGetBufferedAmount(dc);
}

/*
 * Copies the oldest waiting event into the array and returns (type << 56) | (handle << 24) |
 * length. Returns 0 when nothing is waiting, and minus the needed size when the array is too
 * small, in which case the event stays where it is.
 */
JNIEXPORT jlong JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nPoll(JNIEnv *env, jclass, jbyteArray into) {
  std::lock_guard<std::mutex> lock(gLock);
  if (gEvents.empty()) {
    return 0;
  }
  Event &event = gEvents.front();
  jsize capacity = env->GetArrayLength(into);
  jsize size = static_cast<jsize>(event.data.size());
  if (size > capacity) {
    return -static_cast<jlong>(size);
  }
  if (size > 0) {
    env->SetByteArrayRegion(into, 0, size, reinterpret_cast<const jbyte *>(event.data.data()));
  }
  jlong result = (static_cast<jlong>(event.type) << 56) | (static_cast<jlong>(event.handle & 0x3FFFFFFF) << 24)
                 | static_cast<jlong>(size);
  gQueuedBytes -= event.data.size();
  gEvents.pop_front();
  return result;
}

JNIEXPORT void JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nClose(JNIEnv *, jclass, jint handle) {
  int pc = -1;
  int channels[2] = {-1, -1};
  {
    std::lock_guard<std::mutex> lock(gLock);
    Slot *slot = slotOf(handle);
    if (slot == nullptr) {
      return;
    }
    pc = slot->pc;
    channels[kReliable] = slot->channel[kReliable];
    channels[kUnreliable] = slot->channel[kUnreliable];
    // From here on the handle is dead, so callbacks that are still on their way are ignored.
    slot->used = false;
    for (auto it = gEvents.begin(); it != gEvents.end();) {
      if (it->handle == handle) {
        gQueuedBytes -= it->data.size();
        it = gEvents.erase(it);
      } else {
        ++it;
      }
    }
  }
  // Deleting waits for running callbacks to finish, and callbacks take the lock, so the lock
  // must not be held here.
  for (int dc : channels) {
    if (dc >= 0) {
      rtcDeleteDataChannel(dc);
    }
  }
  if (pc >= 0) {
    rtcDeletePeerConnection(pc);
  }
}

JNIEXPORT void JNICALL Java_org_flixelgdx_net_jvm_FlixelJvmRtc_nSetLogLevel(JNIEnv *, jclass, jint level) {
  rtcInitLogger(static_cast<rtcLogLevel>(level), nullptr);
}

} // extern "C"
