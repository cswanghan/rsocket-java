/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.fragmentation;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.rsocket.frame.*;
import java.util.function.Consumer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

/**
 * The implementation of the RSocket fragmentation behavior.
 *
 * @see <a
 *     href="https://github.com/rsocket/rsocket/blob/master/Protocol.md#fragmentation-and-reassembly">Fragmentation
 *     and Reassembly</a>
 */
final class FrameFragmenter {
  static Publisher<ByteBuf> fragmentFrame(
      ByteBufAllocator allocator,
      int mtu,
      ByteBuf frame,
      FrameType frameType,
      boolean encodeLength) {
    frame.retain();
    ByteBuf metadata = getMetadata(frame, frameType);
    ByteBuf data = getData(frame, frameType);
    int streamId = FrameHeaderFlyweight.streamId(frame);
    return Flux.generate(
        new Consumer<SynchronousSink<ByteBuf>>() {
          boolean first = true;

          @Override
          public void accept(SynchronousSink<ByteBuf> sink) {
            ByteBuf byteBuf;
            if (first) {
              first = false;
              byteBuf =
                  encodeFirstFragment(allocator, mtu, frame, frameType, streamId, metadata, data);

            } else {
              byteBuf = encodeFollowsFragment(allocator, mtu, streamId, metadata, data);
            }

            sink.next(encode(allocator, byteBuf, encodeLength));

            if (metadata.isReadable() && data.isReadable()) {
              try {
                sink.complete();
              } finally {
                ReferenceCountUtil.safeRelease(frame);
                ReferenceCountUtil.safeRelease(metadata);
                ReferenceCountUtil.safeRelease(data);
              }
            }
          }
        });
  }

  private static ByteBuf encodeFirstFragment(
      ByteBufAllocator allocator,
      int mtu,
      ByteBuf frame,
      FrameType frameType,
      int streamId,
      ByteBuf metadata,
      ByteBuf data) {
    // subtract the header bytes
    int m = mtu - FrameHeaderFlyweight.size();

    // substract the initial request n
    switch (frameType) {
      case REQUEST_STREAM:
      case REQUEST_CHANNEL:
        m -= Integer.BYTES;
        break;
      default:
    }

    ByteBuf metadataFragment = null;
    if (metadata.isReadable()) {
      // subtract the metadata frame length
      m -= 3;
      int r = Math.min(m, metadata.readableBytes());
      m -= r;
      metadataFragment = metadata.readRetainedSlice(r);
    }

    ByteBuf dataFragment = Unpooled.EMPTY_BUFFER;
    if (m > 0 && data.isReadable()) {
      int r = Math.min(m, data.readableBytes());
      dataFragment = data.readRetainedSlice(r);
    }

    switch (frameType) {
      case REQUEST_FNF:
        return RequestFireAndForgetFrameFlyweight.encode(
            allocator, streamId, true, metadataFragment, dataFragment);
      case REQUEST_STREAM:
        return RequestStreamFrameFlyweight.encode(
            allocator,
            streamId,
            true,
            RequestStreamFrameFlyweight.initialRequestN(frame),
            metadataFragment,
            dataFragment);
      case REQUEST_RESPONSE:
        return RequestResponseFrameFlyweight.encode(
            allocator, streamId, true, metadataFragment, dataFragment);
      case REQUEST_CHANNEL:
        return RequestChannelFrameFlyweight.encode(
            allocator,
            streamId,
            true,
            false,
            RequestChannelFrameFlyweight.initialRequestN(frame),
            metadataFragment,
            dataFragment);
        // Payload and synthetic types
      case PAYLOAD:
        return PayloadFrameFlyweight.encode(
            allocator, streamId, true, false, false, metadata, data);
      case NEXT:
        return PayloadFrameFlyweight.encode(allocator, streamId, true, false, true, metadata, data);
      case NEXT_COMPLETE:
        return PayloadFrameFlyweight.encode(allocator, streamId, true, true, true, metadata, data);
      case COMPLETE:
        return PayloadFrameFlyweight.encode(allocator, streamId, true, true, false, metadata, data);
      default:
        throw new IllegalStateException("unsupported fragment type");
    }
  }

  private static ByteBuf encodeFollowsFragment(
      ByteBufAllocator allocator, int mtu, int streamId, ByteBuf metadata, ByteBuf data) {
    // subtract the header bytes
    int m = mtu - FrameHeaderFlyweight.size();

    ByteBuf metadataFragment = null;
    if (metadata.isReadable()) {
      // subtract the metadata frame length
      m -= 3;
      int r = Math.min(m, metadata.readableBytes());
      m -= r;
      metadataFragment = metadata.readRetainedSlice(r);
    }

    ByteBuf dataFragment = Unpooled.EMPTY_BUFFER;
    if (m > 0 && data.isReadable()) {
      int r = Math.min(m, data.readableBytes());
      dataFragment = data.readRetainedSlice(r);
    }

    boolean follows = !metadata.isReadable() && !data.isReadable();
    return PayloadFrameFlyweight.encode(
        allocator, streamId, follows, false, false, metadataFragment, dataFragment);
  }

  private static ByteBuf getMetadata(ByteBuf frame, FrameType frameType) {
    boolean hasMetadata = FrameHeaderFlyweight.hasMetadata(frame);
    if (hasMetadata) {
      ByteBuf metadata;
      switch (frameType) {
        case REQUEST_FNF:
          metadata = RequestFireAndForgetFrameFlyweight.metadata(frame);
          break;
        case REQUEST_STREAM:
          metadata = RequestStreamFrameFlyweight.metadata(frame);
          break;
        case REQUEST_RESPONSE:
          metadata = RequestResponseFrameFlyweight.metadata(frame);
          break;
        case REQUEST_CHANNEL:
          metadata = RequestChannelFrameFlyweight.metadata(frame);
          break;
          // Payload and synthetic types
        case PAYLOAD:
        case NEXT:
        case NEXT_COMPLETE:
        case COMPLETE:
          metadata = PayloadFrameFlyweight.metadata(frame);
          break;
        default:
          throw new IllegalStateException("unsupported fragment type");
      }
      return metadata.retain();
    } else {
      return Unpooled.EMPTY_BUFFER;
    }
  }

  private static ByteBuf getData(ByteBuf frame, FrameType frameType) {
    ByteBuf data;
    switch (frameType) {
      case REQUEST_FNF:
        data = RequestFireAndForgetFrameFlyweight.data(frame);
        break;
      case REQUEST_STREAM:
        data = RequestStreamFrameFlyweight.data(frame);
        break;
      case REQUEST_RESPONSE:
        data = RequestResponseFrameFlyweight.data(frame);
        break;
      case REQUEST_CHANNEL:
        data = RequestChannelFrameFlyweight.data(frame);
        break;
        // Payload and synthetic types
      case PAYLOAD:
      case NEXT:
      case NEXT_COMPLETE:
      case COMPLETE:
        data = PayloadFrameFlyweight.data(frame);
        break;
      default:
        throw new IllegalStateException("unsupported fragment type");
    }
    return data.retain();
  }

  private static ByteBuf encode(ByteBufAllocator allocator, ByteBuf frame, boolean encodeLength) {
    if (encodeLength) {
      return FrameLengthFlyweight.encode(allocator, frame.readableBytes(), frame).retain();
    } else {
      return frame;
    }
  }
}
