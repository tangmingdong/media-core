/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2015, Telestax Inc and individual contributors
 * by the @authors tag. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.server.impl.rtp;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.mobicents.media.server.component.audio.AudioOutput;
import org.mobicents.media.server.impl.AbstractSink;
import org.mobicents.media.server.impl.rtp.sdp.RTPFormat;
import org.mobicents.media.server.impl.rtp.sdp.RTPFormats;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.spi.dsp.Processor;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.memory.Frame;

/**
 * Implementation of a Media Sink.
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class RtpSink extends AbstractSink {

    private static final long serialVersionUID = -3660168309653874674L;

    private static final Logger logger = Logger.getLogger(RtpSink.class);

    // Media Mixer components
    private static final AudioFormat LINEAR_FORMAT = FormatFactory.createAudioFormat("LINEAR", 8000, 16, 1);
    private final AudioOutput audioOutput;
    private final Processor dsp;

    // RTP processing components
    private RTPFormats formats;
    private final RtpClock rtpClock;
    private final RtpPacket rtpPacket;

    // RTP transport
    private final RtpGateway rtpGateway;

    // Details of last transmitted packet
    private RTPFormat currentFormat;
    private long rtpTimestamp;

    public RtpSink(Scheduler scheduler, RtpClock rtpClock, Processor dsp, RtpGateway rtpGateway) {
        super("output");

        // Media mixer components
        this.audioOutput = new AudioOutput(scheduler, 1);
        this.audioOutput.join(this);
        this.dsp = dsp;

        // RTP processing components
        this.rtpClock = rtpClock;

        // RTP transport
        this.rtpGateway = rtpGateway;
        this.rtpPacket = new RtpPacket(RtpPacket.RTP_PACKET_MAX_SIZE, true);

        // Details of last transmitted packet
        this.rtpTimestamp = 0L;
    }

    public AudioOutput getAudioOutput() {
        return audioOutput;
    }

    /**
     * Sets the supported formats for audio output.
     * 
     * @param formats The supported audio formats.
     */
    public void setFormats(RTPFormats formats) {
        this.formats = formats;
    }

    @Override
    public void onMediaTransfer(Frame frame) throws IOException {
        if (dsp != null && formats != null && !formats.isEmpty()) {
            try {
                // perform transcoding
                frame = dsp.process(frame, LINEAR_FORMAT, this.formats.getFormats().get(0));

                // send the packet to remote peer if media frame is valid
                if (evolveFrame(frame)) {
                    this.rtpGateway.outgoingRtp(this.rtpPacket);
                }
            } catch (Exception e) {
                logger.error("Transcoding error: " + e.getMessage(), e);
            }
        }
    }

    private boolean evolveFrame(Frame frame) {
        // discard frame if format is unknown
        if (frame.getFormat() == null) {
            frame.recycle();
            return false;
        }

        // determine current RTP format if it is unknown
        if (currentFormat == null || !currentFormat.getFormat().matches(frame.getFormat())) {
            currentFormat = formats.getRTPFormat(frame.getFormat());

            // discard packet if format is still unknown
            if (currentFormat == null) {
                frame.recycle();
                return false;
            }

            // update clock rate
            rtpClock.setClockRate(currentFormat.getClockRate());
        }

        // convert frame timestamp to milliseconds
        long frameTimestamp = frame.getTimestamp() / 1000000L;

        // ignore frames with duplicate timestamp
        if (frameTimestamp == this.rtpTimestamp) {
            frame.recycle();
            return false;
        }

        this.rtpTimestamp = rtpClock.convertToRtpTime(frameTimestamp);

        // wrap the RTP packet and return it
        // NOTE 1: the SSRC field is unknown at this point, it must be overwritten by the RTP transport object!
        // NOTE 2: the sequence number field is unknown at this point, it must be overwritten by the RTP transport object!
        rtpPacket.wrap(false, currentFormat.getID(), 0, this.rtpTimestamp, 0L, frame.getData(), frame.getOffset(),
                frame.getLength());
        frame.recycle();
        return true;
    }

    @Override
    public void activate() {
        this.audioOutput.start();
    }

    @Override
    public void deactivate() {
        this.audioOutput.stop();
    }

}