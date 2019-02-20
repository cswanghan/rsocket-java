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
import io.rsocket.DuplexConnection;
import io.rsocket.frame.FrameHeaderFlyweight;
import io.rsocket.frame.FrameLengthFlyweight;
import io.rsocket.frame.FrameType;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static io.rsocket.fragmentation.FrameFragmenter.fragmentFrame;

/**
 * A {@link DuplexConnection} implementation that fragments and reassembles {@link ByteBuf}s.
 *
 * @see <a
 *     href="https://github.com/rsocket/rsocket/blob/master/Protocol.md#fragmentation-and-reassembly">Fragmentation
 *     and Reassembly</a>
 */
public final class FragmentationDuplexConnection implements DuplexConnection {
  private static final Logger logger = LoggerFactory.getLogger(FragmentationDuplexConnection.class);
  private final DuplexConnection delegate;
  private final int mtu;
  private final ByteBufAllocator allocator;
  private final FrameReassembler frameReassembler;
  private final boolean encodeLength;

  public FragmentationDuplexConnection(
      DuplexConnection delegate, ByteBufAllocator allocator, int mtu, boolean encodeLength) {
    this.encodeLength = encodeLength;
    this.allocator = allocator;
    this.delegate = delegate;
    this.mtu = mtu;
    this.frameReassembler = new FrameReassembler(allocator);

    delegate
        .onClose()
        .doFinally(
            s -> {
              frameReassembler.dispose();
            })
        .subscribe();
  }

  private boolean shouldFragment(FrameType frameType, int readableBytes) {
    if (frameType.isFragmentable()) {
      return readableBytes > mtu;
    } else {
      return false;
    }
  }

  @Override
  public Mono<Void> send(Publisher<ByteBuf> frames) {
    return Flux.from(frames).concatMap(this::sendOne).then();
  }

  @Override
  public Mono<Void> sendOne(ByteBuf frame) {
    FrameType frameType = FrameHeaderFlyweight.frameType(frame);
    int readableBytes = frame.readableBytes();
    if (shouldFragment(frameType, readableBytes)) {
      return delegate.send(fragmentFrame(allocator, mtu, frame, frameType, encodeLength));
    } else {
      return delegate.sendOne(encode(frame));
    }
  }

  private ByteBuf encode(ByteBuf frame) {
    if (encodeLength) {
      return FrameLengthFlyweight.encode(allocator, frame.readableBytes(), frame).retain();
    } else {
      return frame;
    }
  }

  @Override
  public Flux<ByteBuf> receive() {
    return delegate.receive().handle(frameReassembler::reassembleFrame);
  }

  @Override
  public Mono<Void> onClose() {
    return delegate.onClose();
  }

  @Override
  public void dispose() {
    delegate.dispose();
  }
}
