package io.rsocket.frame;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public class FrameUtil {
  private FrameUtil() {}

  public static String toString(ByteBuf frame) {
    FrameType frameType = FrameHeaderFlyweight.frameType(frame);
    int streamId = FrameHeaderFlyweight.streamId(frame);
    StringBuilder payload = new StringBuilder();

    payload
        .append("\nFrame => Stream ID: ")
        .append(streamId)
        .append(" Type: ")
        .append(frameType)
        .append(" Flags: 0b")
        .append(Integer.toBinaryString(FrameHeaderFlyweight.flags(frame)))
        .append(" Length: " + frame.readableBytes());

    if (FrameHeaderFlyweight.hasMetadata(frame)) {
      payload.append("\nMetadata:\n");

      ByteBufUtil.appendPrettyHexDump(payload, getMetadata(frame, frameType));
    }

    payload.append("\nData:\n");
    ByteBufUtil.appendPrettyHexDump(payload, getData(frame, frameType));

    return payload.toString();
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
        case METADATA_PUSH:
          metadata = MetadataPushFrameFlyweight.metadata(frame);
          break;
        case SETUP:
          metadata = SetupFrameFlyweight.metadata(frame);
          break;
        case LEASE:
          metadata = LeaseFlyweight.metadata(frame);
          break;
        default:
          return Unpooled.EMPTY_BUFFER;
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
      case SETUP:
        data = SetupFrameFlyweight.data(frame);
        break;
      default:
        return Unpooled.EMPTY_BUFFER;
    }
    return data;
  }
}

/*

 return "Frame => Stream ID: "
        + streamId
        + " Type: "
        + type
        + additionalFlags
        + " Payload: "
        + payload;

@Override
  public String toString() {
    FrameType type = FrameHeaderFlyweight.frameType(content);
    StringBuilder payload = new StringBuilder();
    @Nullable ByteBuf metadata = FrameHeaderFlyweight.sliceFrameMetadata(content);

    if (metadata != null) {
      if (0 < metadata.readableBytes()) {
        payload.append(
            String.format("metadata: \"%s\" ", metadata.toString(StandardCharsets.UTF_8)));
      }
    }

    ByteBuf data = FrameHeaderFlyweight.sliceFrameData(content);
    if (0 < data.readableBytes()) {
      payload.append(String.format("data: \"%s\" ", data.toString(StandardCharsets.UTF_8)));
    }

    long streamId = FrameHeaderFlyweight.streamId(content);

    String additionalFlags = "";
    switch (type) {
      case LEASE:
        additionalFlags = " Permits: " + Lease.numberOfRequests(this) + " TTL: " + Lease.ttl(this);
        break;
      case REQUEST_N:
        additionalFlags = " RequestN: " + RequestN.requestN(this);
        break;
      case KEEPALIVE:
        additionalFlags = " Respond flag: " + Keepalive.hasRespondFlag(this);
        break;
      case REQUEST_STREAM:
      case REQUEST_CHANNEL:
        additionalFlags = " Initial Request N: " + Request.initialRequestN(this);
        break;
      case ERROR:
        additionalFlags = " Error code: " + Error.errorCode(this);
        break;
      case SETUP:
        int version = Setup.version(this);
        additionalFlags =
            " Version: "
                + VersionFlyweight.toString(version)
                + " keep-alive interval: "
                + Setup.keepaliveInterval(this)
                + " max lifetime: "
                + Setup.maxLifetime(this)
                + " metadata mime type: "
                + Setup.metadataMimeType(this)
                + " data mime type: "
                + Setup.dataMimeType(this);
        break;
    }

    return "Frame => Stream ID: "
        + streamId
        + " Type: "
        + type
        + additionalFlags
        + " Payload: "
        + payload;
}
 */
