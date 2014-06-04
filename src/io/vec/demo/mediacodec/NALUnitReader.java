package io.vec.demo.mediacodec;


import java.io.IOException;
import java.util.Arrays;

/**
 * Input stream that automatically unwraps NAL units and allows for NAL unit
 * navigation
 * 
 * @author Stanislav Vitvitskiy
 */
public class NALUnitReader {
    private final IsoBufferWrapper src;


    public NALUnitReader(IsoBufferWrapper src) throws IOException {
        this.src = src;
    }

    public IsoBufferWrapper nextNALUnit() throws IOException {
        if (src.remaining() < 5) {
            return null;
        }

        long start = -1;
        do {
            byte[] marker = new byte[4];
            if (src.remaining() >= 4) {
                src.read(marker);
                if (Arrays.equals(new byte[] { 0, 0, 0, 1 }, marker)) {
                    if (start == -1) {
						start = src.position() - 4;
                    } else {
                        src.position(src.position() - 4);
                        return src.getSegment(start, src.position() - start);
                    }
                } else {
                    src.position(src.position() - 3); // advance 1 byte at a
                                                      // time
                }
            } else {
                return src.getSegment(start, src.size() - start); // rest
            }

        } while (src.remaining() > 0);
        return src.getSegment(start, src.position());

    }
}