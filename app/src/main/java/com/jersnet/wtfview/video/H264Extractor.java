package com.jersnet.wtfview.video;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.ts.H264Reader;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.extractor.ts.SeiReader;

import java.io.IOException;
import java.util.ArrayList;

import static com.google.android.exoplayer2.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;

public final class H264Extractor implements Extractor {

    public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new H264Extractor()};

    private static int MAX_SYNC_FRAME_SIZE = 131072;

    private long firstSampleTimestampUs;
    private static long sampleTime = 10000;
    private final H264Reader reader;
    private final ParsableByteArray sampleData;

    private boolean startedPacket;

    public H264Extractor() {
        this(0);
    }

    public H264Extractor(int mMaxSyncFrameSize, int mSampleTime) {
        this(0, mMaxSyncFrameSize, mSampleTime);
    }

    public H264Extractor(long firstSampleTimestampUs) {
        this(firstSampleTimestampUs, MAX_SYNC_FRAME_SIZE, (int) sampleTime);
    }

    public H264Extractor(long firstSampleTimestampUs, int mMaxSyncFrameSize, int mSampleTime) {
        MAX_SYNC_FRAME_SIZE = mMaxSyncFrameSize;
        sampleTime = mSampleTime;
        this.firstSampleTimestampUs = firstSampleTimestampUs;
        reader = new H264Reader(new SeiReader(new ArrayList<Format>()),false,true);
        sampleData = new ParsableByteArray(MAX_SYNC_FRAME_SIZE);
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        return true;
    }

    @Override
    public void init(ExtractorOutput output) {
        reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(0, 1));
        output.endTracks();
        output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
    }

    @Override
    public void seek(long position, long timeUs) {
        startedPacket = false;
        reader.seek();
    }

    @Override
    public void release() {

    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        int bytesRead = input.read(sampleData.getData(), 0, MAX_SYNC_FRAME_SIZE);
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            return RESULT_END_OF_INPUT;
        }

        sampleData.setPosition(0);
        sampleData.setLimit(bytesRead);
        firstSampleTimestampUs += sampleTime;
        reader.packetStarted(firstSampleTimestampUs, FLAG_DATA_ALIGNMENT_INDICATOR);
        reader.consume(sampleData);
        return RESULT_CONTINUE;
    }

}
