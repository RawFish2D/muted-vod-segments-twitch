package ua.rawfish2d.vodcutter;

import com.beust.jcommander.JCommander;
import org.delfic.videolister.ListLatestFilianVideos;

import java.io.*;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VodCutter {
	public static void main(String[] args) {
		new VodCutter(args);
	}

	private final List<String> localVods;
	private final Config config = new Config();

	public VodCutter(String[] args) {
		// disable annoying selenium logs
		System.setProperty("webdriver.chrome.silentOutput", "true");
		Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF);

		// arrange command line arguments in class fields
		JCommander.newBuilder()
				.addObject(config)
				.build()
				.parse(args);

		// check if paths have \\ at the end, and add it if they don't
		if (!config.getVodsFolder().endsWith("\\") && !config.getVodsFolder().endsWith("/")) {
			config.setVodsFolder(config.getVodsFolder() + "\\");
		}
		if (!config.getOutputFolder().endsWith("\\") && !config.getOutputFolder().endsWith("/")) {
			config.setOutputFolder(config.getOutputFolder() + "\\");
		}

		final String vodsFolder = config.getVodsFolder();
		final String outputFolder = config.getOutputFolder();
		File outputFolderFile = new File(outputFolder);
		File inputFolderFile = new File(vodsFolder);
		// create output folder for clips if it's not a directory or doesn't exist
		if (!outputFolderFile.isDirectory() || !outputFolderFile.exists()) {
			if (!outputFolderFile.mkdir()) {
				throw new RuntimeException("Unable to create/find output folder '" + outputFolderFile + "'!");
			}
		}
		// check if input folder is a directory and does it exist
		if (!inputFolderFile.isDirectory() || !inputFolderFile.exists()) {
			throw new RuntimeException("Input folder '" + inputFolderFile + "' doesn't exist!");
		}
		// get list of files in vods folder
		localVods = listFilesUsingJavaIO(vodsFolder);

		// get twitch vods id's and date
		final String twitchLink = "https://www.twitch.tv/" + config.getChannelName() + "/videos?filter=archives&sort=time";
		final var map = ListLatestFilianVideos.getVodLinksPerDate(twitchLink);
		final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd").withLocale(Locale.ROOT);
		for (String vodID : map.keySet()) {
			// find local vod with same date
			final LocalDate date = map.get(vodID);
			vodID = vodID.replace("/videos/", "");
			vodID = vodID.replace("?filter=archives&sort=time", "");
			final String formattedDate = dateTimeFormatter.format(date);
			final String vodFileName = getVodFileNameWithDate(formattedDate);
			// if local vod with same date found then
			if (vodFileName != null) {
				System.out.println("\n====================");
				System.out.printf("Twitch VOD. Date: %s link: %s\n", formattedDate, vodID);
				System.out.printf("Found local VOD with same date %s\n", vodFileName);
				System.out.printf("Local VOD duration: %s\n", getVodDuration(vodsFolder + vodFileName));

				final int attemptsMax = 5;
				String m3u8link = "";
				for (int a = 0; a < attemptsMax; ++a) {
					m3u8link = getM3U8link(vodID);
					if (m3u8link.equals("null")) {
						System.err.println("Error! Can't get m3u8 link for some reason...\nTrying again... " + a + "/" + attemptsMax);
					} else {
						break;
					}
				}
				String m3u8data = "";
				for (int a = 0; a < attemptsMax; ++a) {
					try {
						m3u8data = downloadM3U8(m3u8link, vodID + "_" + formattedDate);
					} catch (IOException e) {
						e.printStackTrace();
						System.err.println("Exception while downloading m3u8 file...\nTrying again... " + a + "/" + attemptsMax);
					}
				}
				final List<Segment> segments = parseM3U8(m3u8data);
				// loop for cutting out unmuted segments from local vod
				int clipID = 0;
				System.out.println("Starting ffmpeg to cut out unmuted parts from " + vodFileName);
				for (Segment segment : segments) {
					System.out.println("Cutting out part " + segment.toString() + " from VOD (" + formattedDate + ") " + vodFileName);
					cutVod(vodFileName, segment.getStartTime(), segment.getDurationTime(), clipID, formattedDate);
					clipID++;
				}
			}
		}
		System.out.println("Everything is done!");
	}

	private String getVodDuration(String vodFileName) {
		final String command = "ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 -sexagesimal \"" + vodFileName + "\"";
		final String result = execCmd(command);
		if (result != null) {
			return result.trim();
		}
		return "null";
	}

	private String getM3U8link(String vodID) {
		final String command = "streamlink --url https://www.twitch.tv/videos/" + vodID + " --default-stream best --stream-url";
		final String result = execCmd(command);
		if (result != null) {
			return result.trim();
		}
		return "null";
	}

	private String downloadM3U8(String link, String fileName) throws IOException {
		final StringBuilder m3u8 = new StringBuilder();
		try (BufferedInputStream in = new BufferedInputStream(new URL(link).openStream());
		     FileOutputStream fileOutputStream = new FileOutputStream(fileName + ".m3u8")) {
			byte[] dataBuffer = new byte[1024];
			int bytesRead;

			while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
				String str = new String(dataBuffer, 0, bytesRead);
				m3u8.append(str);
				fileOutputStream.write(dataBuffer, 0, bytesRead); // write it to file for debugging purposes
			}
			fileOutputStream.flush();
		}
		return m3u8.toString();
	}

	private List<Segment> parseM3U8(final String m3u8data) {
		final List<Segment> segments = new ArrayList<>();
		final String[] m3u8lines = m3u8data.split("\n");
		double currentSeconds = 0;
		double prevDuration = 0;
		double vodDurationSeconds = 0;

		double mutedSegmentStartSeconds = 0;
		boolean inMutedSegment = false;
		for (int a = 0; a < m3u8lines.length; ++a) {
			final String line = m3u8lines[a].toUpperCase(); // to upper case just in case xd
			if (!line.equals("#EXTM3U") && a == 0) {
				System.out.println("Not M3U8!");
				break;
			}
			if (line.startsWith("#EXTINF:")) {
				final String[] split = line.split(":"); // #EXTINF:10.000,
				final String time = split[1].replaceAll(",", ""); // remove , from the end of the line
				prevDuration = Double.parseDouble(time);
			}
			if (line.startsWith("#EXT-X-TWITCH-TOTAL-SECS:")) {
				final String[] split = line.split(":"); // #EXT-X-TWITCH-TOTAL-SECS:15500.963
				final String time = split[1].replaceAll(",", ""); // remove , from the end of the line
				vodDurationSeconds = Double.parseDouble(time);
			}
			if (line.endsWith(".TS")) { // 22-MUTED.TS
				if (line.contains("MUTED")) {
					if (!inMutedSegment) {
						inMutedSegment = true;
						mutedSegmentStartSeconds = currentSeconds;
					}
				} else if (inMutedSegment) {
					segments.add(new Segment(mutedSegmentStartSeconds, currentSeconds + prevDuration, vodDurationSeconds, config.getSegmentOffsetSeconds()));
					inMutedSegment = false;
					mutedSegmentStartSeconds = 0;
				}
				currentSeconds += prevDuration;
			}
			if (line.equals("#EXT-X-ENDLIST")) {
				break;
			}
		}
		segments.forEach(segment -> System.out.println("Muted segment: " + segment.toString()));
		return segments;
	}

	private void cutVod(String vodFileName, String segmentStartTime, String segmentDurationTime, int clipID, String vodDate) {
		final String clipFileName = config.getOutputFolder() + config.getChannelName() + "_clip" + clipID + "_" + segmentStartTime.replace(":", "-") + "_" + vodDate;
		final String command = "ffmpeg -hide_banner -ss " + segmentStartTime +
				" -accurate_seek -i \"" + config.getVodsFolder() + vodFileName + "\" -t " + segmentDurationTime +
				" -reset_timestamps 1 -async 1 -c:v copy -movflags faststart -c:a copy \"" + clipFileName + ".mp4\"";
		// result of this call is always null for some reason
		execCmd(command);
	}

	private String getVodFileNameWithDate(String formattedDate) {
		for (String vodName : localVods) {
			if (vodName.startsWith(config.getChannelName() + "_" + formattedDate + "_") && (vodName.endsWith(".mp4") || vodName.endsWith(".mkv"))) {
				return vodName;
			}
		}
		return null;
	}

	private String execCmd(String cmd) {
		String result = null;
		try (InputStream inputStream = Runtime.getRuntime().exec(cmd).getInputStream();
		     Scanner s = new Scanner(inputStream).useDelimiter("\\A")) {
			result = s.hasNext() ? s.next() : null;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return result;
	}

	private List<String> listFilesUsingJavaIO(String dir) {
		return Stream.of(new File(dir).listFiles())
				.filter(file -> !file.isDirectory())
				.map(File::getName)
				.collect(Collectors.toList());
	}
}
