package ua.rawfish2d.vodcutter;

import java.time.Duration;
import java.util.Locale;

public class Segment {
	private Duration startTime;
	private Duration durationTime;

	public Segment(double startSeconds, double endSeconds, double vodDurationSeconds, long segmentClipOffsetSeconds) {
		long vodDurationS = (long) Math.floor(vodDurationSeconds);
		long start = (long) Math.floor(startSeconds);
		long length = (long) Math.floor(endSeconds - startSeconds);
		// it's is possible that muted segment will at the start of the vod
		// so this code in necessary to avoid negative time
		start = clamp(start - segmentClipOffsetSeconds, 0, start);
		// and also to avoid time that is more than vod duration
		length = clamp(length + (segmentClipOffsetSeconds * 2), 0, vodDurationS);
		startTime = Duration.ofSeconds(start);
		durationTime = Duration.ofSeconds(length);
	}

	private static long clamp(long val, long min, long max) {
		return Math.max(min, Math.min(max, val));
	}

	public String getStartTime() {
		return String.format(Locale.US, "%02d:%02d:%02d", startTime.toHoursPart(), startTime.toMinutesPart(), startTime.toSecondsPart());
	}

	public String getDurationTime() {
		return String.format(Locale.US, "%02d:%02d:%02d", durationTime.toHoursPart(), durationTime.toMinutesPart(), durationTime.toSecondsPart());
	}

	@Override
	public String toString() {
		return getStartTime() + "-" + getDurationTime();
	}
}
