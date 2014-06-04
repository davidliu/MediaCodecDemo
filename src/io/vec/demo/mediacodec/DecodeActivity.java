package io.vec.demo.mediacodec;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class DecodeActivity extends Activity implements SurfaceHolder.Callback {
	private static final String TAG = DecodeActivity.class.getSimpleName();
	private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/raw.h264";
	private static final String SAMPLE_MP4 = Environment.getExternalStorageDirectory() + "/video.mp4";


	private PlayerThread mPlayer = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		SurfaceView sv = new SurfaceView(this);
		sv.getHolder().addCallback(this);
		setContentView(sv);
	}

	protected void onDestroy() {
		super.onDestroy();
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		if (mPlayer == null) {
			mPlayer = new PlayerThread(holder.getSurface());
			mPlayer.start();
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		if (mPlayer != null) {
			mPlayer.interrupt();
		}
	}

	private class PlayerThread extends Thread {
		private MediaCodec decoder;
		private Surface surface;

		public PlayerThread(Surface surface) {
			this.surface = surface;
		}

		@Override
		public void run() {
			try {
				IsoBufferWrapper wrapper = new IsoBufferWrapper(new File(SAMPLE));
				NALUnitReader reader = new NALUnitReader(wrapper);

				MediaFormat format = MediaFormat.createVideoFormat("video/avc", 320, 560);
				String spsHex = "00 00 00 01 67 64 00 15 AC D9 41 40 47 A1 00 00 03 00 01 00 00 03 00 32 0F 16 2D 96";
				String ppsHex = "00 00 00 01 68 EB E3 CB 22 C0";

				String[] spsSplit = spsHex.split(" ");
				String[] ppsSplit = ppsHex.split(" ");

				byte[] spsHeader = new byte[spsSplit.length];
				byte[] ppsHeader = new byte[ppsSplit.length];

				for (int i = 0; i < spsHeader.length; i++) {
					spsHeader[i] = (byte) (int) Integer.decode("0x" + spsSplit[i]);
				}
				for (int i = 0; i < ppsHeader.length; i++) {
					ppsHeader[i] = (byte) (int) Integer.decode("0x" + ppsSplit[i]);
				}
				format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);

				format.setByteBuffer("csd-0", ByteBuffer.wrap(spsHeader));
				format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsHeader));

				decoder = MediaCodec.createDecoderByType("video/avc");
				decoder.configure(format, surface, null, 0);

				if (decoder == null) {
					Log.e("DecodeActivity", "Can't find video info!");
					return;
				}

				decoder.start();

				ByteBuffer[] inputBuffers = decoder.getInputBuffers();
				ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
				BufferInfo info = new BufferInfo();
				boolean isEOS = false;
				long startMs = System.currentTimeMillis();

				while (!Thread.interrupted()) {
					if (!isEOS) {
						int inIndex = decoder.dequeueInputBuffer(10000);
						if (inIndex >= 0) {
							ByteBuffer buffer = inputBuffers[inIndex];
							IsoBufferWrapper unit = reader.nextNALUnit();

							if (unit == null) {
								// We shouldn't stop the playback at this point,
								// just pass the EOS flag to decoder, we will
								// get it again from
								// the dequeueOutputBuffer
								Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
								decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
								isEOS = true;
							} else {
								buffer.clear();
								byte[] buf = new byte[1024];
								int offset = 0;
								while (true) {
									int bytes = unit.read(buf);
									if (bytes <= 0) {
										break;
									}
									buffer.put(buf, 0, bytes);
									offset += bytes;

									if (bytes < buf.length) {
										break;
									}
								}
								int totalSize = offset;

								int flags = 0;

								byte firstByte = buffer.get(0);
								if ((firstByte & 0x1F) == 0x08 || (firstByte & 0x1F) == 0x07) {
									flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
								} else {
									decoder.queueInputBuffer(inIndex, 0, totalSize, 0, flags);
								}
							}
						}
					}

					int outIndex = decoder.dequeueOutputBuffer(info, 100000);
					switch (outIndex) {
						case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
							Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
							outputBuffers = decoder.getOutputBuffers();
							break;
						case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
							Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
							break;
						case MediaCodec.INFO_TRY_AGAIN_LATER:
							Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
							break;
						default:
							ByteBuffer buffer = outputBuffers[outIndex];
							Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, "
							        + buffer);

							// We use a very simple clock to keep the video FPS,
							// or the video
							// playback will be too fast
							while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
								try {
									sleep(10);
								} catch (InterruptedException e) {
									e.printStackTrace();
									break;
								}
							}
							decoder.releaseOutputBuffer(outIndex, true);
							break;
					}

					// All decoded frames have been rendered, we can stop
					// playing now
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
						break;
					}
				}

				decoder.stop();
				decoder.release();
			} catch (Exception ex) {
				Log.e(TAG, "Exception: ", ex);
			}
		}
	}

	private class MP4PlayerThread extends Thread {
		private MediaExtractor extractor;
		private MediaCodec decoder;
		private Surface surface;

		public MP4PlayerThread(Surface surface) {
			this.surface = surface;
		}

		@Override
		public void run() {
			extractor = new MediaExtractor();
			try {
				extractor.setDataSource(SAMPLE_MP4);
            } catch (IOException e1) {
            	Log.e(TAG, "error setting data source.", e1);
            	throw new RuntimeException(e1);
            }

			for (int i = 0; i < extractor.getTrackCount(); i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime.startsWith("video/")) {
					extractor.selectTrack(i);
					decoder = MediaCodec.createDecoderByType(mime);
					decoder.configure(format, surface, null, 0);
					break;
				}
			}

			if (decoder == null) {
				Log.e("DecodeActivity", "Can't find video info!");
				return;
			}

			decoder.start();

			ByteBuffer[] inputBuffers = decoder.getInputBuffers();
			ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
			BufferInfo info = new BufferInfo();
			boolean isEOS = false;
			long startMs = System.currentTimeMillis();

			while (!Thread.interrupted()) {
				if (!isEOS) {
					int inIndex = decoder.dequeueInputBuffer(10000);
					if (inIndex >= 0) {
						ByteBuffer buffer = inputBuffers[inIndex];
						int sampleSize = extractor.readSampleData(buffer, 0);
						if (sampleSize < 0) {
							// We shouldn't stop the playback at this point,
							// just pass the EOS
							// flag to decoder, we will get it again from the
							// dequeueOutputBuffer
							Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
							decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							isEOS = true;
						} else {
							byte[] buf = new byte[sampleSize];
							buffer.mark();
							buffer.get(buf);
							buffer.reset();
							decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
							extractor.advance();
						}
					}
				}

				int outIndex = decoder.dequeueOutputBuffer(info, 10000);
				switch (outIndex) {
					case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
						Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
						outputBuffers = decoder.getOutputBuffers();
						break;
					case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
						Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
						break;
					case MediaCodec.INFO_TRY_AGAIN_LATER:
						Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
						break;
					default:
						ByteBuffer buffer = outputBuffers[outIndex];
						Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, "
						        + buffer);

						// We use a very simple clock to keep the video FPS, or
						// the video
						// playback will be too fast
						while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
							try {
								sleep(10);
							} catch (InterruptedException e) {
								e.printStackTrace();
								break;
							}
						}
						decoder.releaseOutputBuffer(outIndex, true);
						break;
				}

				// All decoded frames have been rendered, we can stop playing
				// now
				if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
					break;
				}
			}

			decoder.stop();
			decoder.release();
			extractor.release();
		}
	}

}