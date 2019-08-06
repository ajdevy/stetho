/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.jsonrpc;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.nio.channels.NotYetConnectedException;
import java.util.HashMap;
import java.util.Map;

import android.database.Observable;

import com.facebook.stetho.inspector.jsonrpc.protocol.JsonRpcRequest;
import com.facebook.stetho.common.Util;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.websocket.SimpleSession;

import org.json.JSONObject;

@ThreadSafe
public class JsonRpcPeer {
  private final SimpleSession mPeer;
  private final ObjectMapper mObjectMapper;

  @GuardedBy("this")
  private long mNextRequestId;

  @GuardedBy("this")
  private final Map<Long, PendingRequest> mPendingRequests = new HashMap<>();

  private final DisconnectObservable mDisconnectObservable = new DisconnectObservable();

  public JsonRpcPeer(ObjectMapper objectMapper, SimpleSession peer) {
    mObjectMapper = objectMapper;
    mPeer = Util.throwIfNull(peer);
  }

  public SimpleSession getWebSocket() {
    return mPeer;
  }

  public void invokeMethod(String method, Object paramsObject,
      @Nullable PendingRequestCallback callback)
      throws NotYetConnectedException {
    Util.throwIfNull(method);

    Long requestId = (callback != null) ? preparePendingRequest(callback) : null;

    // magic, can basically convert anything for some amount of runtime overhead...
    JSONObject params = mObjectMapper.convertValue(paramsObject, JSONObject.class);

    JsonRpcRequest message = new JsonRpcRequest(requestId, method, params);
    String requestString;
    JSONObject jsonObject = mObjectMapper.convertValue(message, JSONObject.class);
    requestString = jsonObject.toString();
    mPeer.sendText(requestString);
  }

  public void registerDisconnectReceiver(DisconnectReceiver callback) {
    mDisconnectObservable.registerObserver(callback);
  }

  public void unregisterDisconnectReceiver(DisconnectReceiver callback) {
    mDisconnectObservable.unregisterObserver(callback);
  }

  public void invokeDisconnectReceivers() {
    mDisconnectObservable.onDisconnect();
  }

  private synchronized long preparePendingRequest(PendingRequestCallback callback) {
    long requestId = mNextRequestId++;
    mPendingRequests.put(requestId, new PendingRequest(requestId, callback));
    return requestId;
  }

  public synchronized PendingRequest getAndRemovePendingRequest(long requestId) {
    return mPendingRequests.remove(requestId);
  }

  private static class DisconnectObservable extends Observable<DisconnectReceiver> {
    public void onDisconnect() {
      for (int i = 0, N = mObservers.size(); i < N; ++i) {
        final DisconnectReceiver observer = mObservers.get(i);
        observer.onDisconnect();
      }
    }
  }
}