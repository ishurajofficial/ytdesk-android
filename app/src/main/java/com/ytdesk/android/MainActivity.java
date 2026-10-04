package com.ytdesk.android;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.yausername.youtubedl_android.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "ytdesk_android";
    private static final String HISTORY_KEY = "history";
    private static final String EMBED_METADATA_KEY = "embed_metadata";
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FormatChoice> videoChoices = new ArrayList<>();
    private final List<FormatChoice> audioChoices = new ArrayList<>();
    private SharedPreferences preferences;

    private TextInputEditText urlInput;
    private MaterialButton analyzeButton;
    private MaterialButton cancelButton;
    private MaterialButton openFileButton;
    private MaterialButton openFolderButton;
    private LinearLayout completedActions;
    private ImageView thumbnail;
    private TextView videoTitle;
    private TextView videoDetails;
    private LinearLayout videoFormats;
    private LinearLayout audioFormats;
    private LinearLayout historyList;
    private View videoCard;
    private View formatsTitle;
    private View videoFormatsLabel;
    private View audioFormatsLabel;
    private View downloadCard;
    private TextView downloadStatus;
    private TextView downloadPercent;
    private TextView downloadInfo;
    private LinearProgressIndicator downloadProgress;

    private VideoInfo currentVideo;
    private String processId;
    private Uri lastSavedUri;
    private volatile boolean downloading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        bindActions();
        renderHistory();
        initializeMediaTools();
        String sharedText = getSharedText(getIntent());
        if (sharedText != null) urlInput.setText(sharedText);
    }

    private void bindViews() {
        urlInput = findViewById(R.id.urlInput);
        analyzeButton = findViewById(R.id.analyzeButton);
        cancelButton = findViewById(R.id.cancelButton);
        openFileButton = findViewById(R.id.openFileButton);
        openFolderButton = findViewById(R.id.openFolderButton);
        completedActions = findViewById(R.id.completedActions);
        thumbnail = findViewById(R.id.thumbnail);
        videoTitle = findViewById(R.id.videoTitle);
        videoDetails = findViewById(R.id.videoDetails);
        videoFormats = findViewById(R.id.videoFormats);
        audioFormats = findViewById(R.id.audioFormats);
        historyList = findViewById(R.id.historyList);
        videoCard = findViewById(R.id.videoCard);
        formatsTitle = findViewById(R.id.formatsTitle);
        videoFormatsLabel = findViewById(R.id.videoFormatsLabel);
        audioFormatsLabel = findViewById(R.id.audioFormatsLabel);
        downloadCard = findViewById(R.id.downloadCard);
        downloadStatus = findViewById(R.id.downloadStatus);
        downloadPercent = findViewById(R.id.downloadPercent);
        downloadInfo = findViewById(R.id.downloadInfo);
        downloadProgress = findViewById(R.id.downloadProgress);
    }

    private void bindActions() {
        analyzeButton.setOnClickListener(view -> analyze());
        findViewById(R.id.settingsButton).setOnClickListener(view -> showSettings());
        cancelButton.setOnClickListener(view -> cancelDownload());
        openFileButton.setOnClickListener(view -> openSavedFile());
        openFolderButton.setOnClickListener(view -> openDownloads());
        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                analyze();
                return true;
            }
            return false;
        });
    }

    private void initializeMediaTools() {
        worker.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplicationContext());
                FFmpeg.getInstance().init(getApplicationContext());
            } catch (YoutubeDLException error) {
                mainHandler.post(() -> toast("Media tools could not start: " + friendly(error)));
            }
        });
    }

    private void analyze() {
        final String original = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();
        if (original.isEmpty()) {
            toast("Paste a YouTube video URL to continue.");
            return;
        }
        final String normalized;
        try {
            normalized = normalizeYouTubeUrl(original);
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
            return;
        }
        hideKeyboard();
        analyzeButton.setEnabled(false);
        analyzeButton.setText("Analyzing…");
        videoCard.setVisibility(View.GONE);
        setFormatsVisible(false);
        worker.execute(() -> {
            try {
                VideoInfo info = YoutubeDL.getInstance().getInfo(normalized);
                mainHandler.post(() -> showVideo(info, normalized));
            } catch (Exception error) {
                mainHandler.post(() -> toast(friendly(error)));
            } finally {
                mainHandler.post(() -> {
                    analyzeButton.setEnabled(true);
                    analyzeButton.setText("Analyze");
                });
            }
        });
    }

    private void showVideo(VideoInfo info, String url) {
        currentVideo = info;
        urlInput.setText(url);
        videoTitle.setText(firstNonEmpty(info.getTitle(), info.getFulltitle(), "YouTube video"));
        String uploader = firstNonEmpty(info.getUploader(), info.getExtractor(), "YouTube");
        videoDetails.setText(uploader + "  ·  " + formatDuration(info.getDuration()));
        if (!TextUtils.isEmpty(info.getThumbnail())) {
            Glide.with(this).load(info.getThumbnail()).centerCrop().into(thumbnail);
        } else {
            thumbnail.setImageDrawable(null);
        }
        videoCard.setVisibility(View.VISIBLE);
        buildChoices(info);
        renderChoices();
        setFormatsVisible(true);
        toast("Video details are ready.");
    }

    private void buildChoices(VideoInfo info) {
        videoChoices.clear();
        audioChoices.clear();
        Map<Integer, VideoFormat> bestByHeight = new HashMap<>();
        if (info.getFormats() != null) {
            for (VideoFormat format : info.getFormats()) {
                String videoCodec = format.getVcodec();
                if (format.getHeight() <= 0 || TextUtils.isEmpty(format.getFormatId()) ||
                        TextUtils.isEmpty(videoCodec) || "none".equals(videoCodec)) continue;
                VideoFormat previous = bestByHeight.get(format.getHeight());
                if (previous == null || preferFormat(format, previous)) bestByHeight.put(format.getHeight(), format);
            }
        }
        List<Integer> heights = new ArrayList<>(bestByHeight.keySet());
        heights.sort(Collections.reverseOrder());
        if (!heights.isEmpty()) {
            VideoFormat best = bestByHeight.get(heights.get(0));
            videoChoices.add(new FormatChoice("Best available · " + heights.get(0) + "p", "bestvideo+bestaudio/best", "video", estimateVideoSize(info, heights.get(0), best), 0));
            for (int height : heights) {
                VideoFormat format = bestByHeight.get(height);
                String selector = format.getFormatId();
                if (TextUtils.isEmpty(format.getAcodec()) || "none".equals(format.getAcodec())) {
                    selector = selector + "+bestaudio[ext=m4a]/" + selector + "+bestaudio/best";
                }
                videoChoices.add(new FormatChoice(height + "p", selector, "video", formatSize(format), height));
            }
        }
        int[] bitrates = {320, 256, 192, 128};
        for (int bitrate : bitrates) {
            long approximate = info.getDuration() > 0 ? (long) info.getDuration() * bitrate * 1000L / 8L : 0L;
            audioChoices.add(new FormatChoice(bitrate + " kbps", "bestaudio/best", "audio", readableSize(approximate), bitrate));
        }
    }

    private boolean preferFormat(VideoFormat candidate, VideoFormat current) {
        boolean candidateMp4 = "mp4".equalsIgnoreCase(candidate.getExt());
        boolean currentMp4 = "mp4".equalsIgnoreCase(current.getExt());
        if (candidateMp4 != currentMp4) return candidateMp4;
        return candidate.getTbr() > current.getTbr();
    }

    private String estimateVideoSize(VideoInfo info, int height, VideoFormat format) {
        String available = formatSize(format);
        if (!"Size varies".equals(available)) return available;
        if (info.getDuration() <= 0) return "Size varies";
        long estimate = (long) info.getDuration() * Math.max(300_000L, height * 650L) / 8L;
        return "~" + readableSize(estimate) + " estimate";
    }

    private String formatSize(VideoFormat format) {
        long size = Math.max(format.getFileSize(), format.getFileSizeApproximate());
        return size > 0 ? "~" + readableSize(size) : "Size varies";
    }

    private void renderChoices() {
        videoFormats.removeAllViews();
        audioFormats.removeAllViews();
        for (FormatChoice choice : videoChoices) videoFormats.addView(choiceCard(choice));
        for (FormatChoice choice : audioChoices) audioFormats.addView(choiceCard(choice));
        findViewById(R.id.videoFormatsLabel).setVisibility(videoChoices.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.audioFormatsLabel).setVisibility(audioChoices.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View choiceCard(FormatChoice choice) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.yt_surface));
        card.setRadius(dp(14));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.yt_border));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
        TextView primary = text(choice.label + ("audio".equals(choice.kind) ? " MP3" : " MP4"), 15, R.color.yt_text, true);
        TextView secondary = text(choice.size, 12, R.color.yt_muted, false);
        labels.addView(primary);
        labels.addView(secondary);
        row.addView(labels, labelParams);

        MaterialButton download = new MaterialButton(this);
        download.setText("Download");
        download.setTextAllCaps(false);
        download.setCornerRadius(dp(11));
        download.setMinHeight(dp(42));
        download.setInsetTop(0);
        download.setInsetBottom(0);
        download.setOnClickListener(view -> startDownload(choice));
        row.addView(download, new LinearLayout.LayoutParams(-2, dp(42)));
        card.addView(row);
        return card;
    }

    private void startDownload(FormatChoice choice) {
        if (downloading || currentVideo == null) return;
        String url = currentVideo.getWebpageUrl();
        if (TextUtils.isEmpty(url)) url = urlInput.getText() == null ? "" : urlInput.getText().toString();
        final String normalized;
        try {
            normalized = normalizeYouTubeUrl(url);
        } catch (IllegalArgumentException error) {
            toast("Analyze the link again before downloading.");
            return;
        }

        downloading = true;
        processId = UUID.randomUUID().toString();
        final String currentProcessId = processId;
        lastSavedUri = null;
        downloadCard.setVisibility(View.VISIBLE);
        completedActions.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(0);
        downloadPercent.setText("0%");
        downloadStatus.setText("Starting download…");
        downloadInfo.setText("Preparing a file in Downloads/YtDesk");

        worker.execute(() -> {
            Uri savedUri = null;
            try {
                File stage = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging");
                if (!stage.exists() && !stage.mkdirs()) throw new IllegalStateException("Could not create a temporary download folder.");
                String token = UUID.randomUUID().toString().substring(0, 8);
                String title = safeFilename(firstNonEmpty(currentVideo.getTitle(), currentVideo.getFulltitle(), "YouTube video"));
                String id = firstNonEmpty(currentVideo.getId(), "video");
                String template = new File(stage, title + " [" + id + "] " + token + ".%(ext)s").getAbsolutePath();
                YoutubeDLRequest request = new YoutubeDLRequest(normalized);
                request.addOption("--no-warnings");
                request.addOption("--no-playlist");
                request.addOption("--newline");
                request.addOption("-o", template);
                request.addOption("--print", "after_move:__YTDESK_FILE__%(filepath)s");
                if (preferences.getBoolean(EMBED_METADATA_KEY, false)) request.addOption("--embed-metadata");
                if ("audio".equals(choice.kind)) {
                    request.addOption("-f", "bestaudio/best");
                    request.addOption("--extract-audio");
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", choice.value + "K");
                } else {
                    request.addOption("-f", choice.value);
                    request.addOption("--merge-output-format", "mp4");
                }

                final String progressProcessId = currentProcessId;
                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, progressProcessId, false,
                        (progress, eta, line) -> {
                            mainHandler.post(() -> updateProgress(progress, eta, line));
                            return kotlin.Unit.INSTANCE;
                        });

                File result = findOutput(response.getOut(), stage, token);
                if (result == null) throw new IllegalStateException("The media transfer ended, but no output file was found.");
                savedUri = saveToDownloads(result, title, id, "audio".equals(choice.kind));
                Uri completedUri = savedUri;
                mainHandler.post(() -> completeDownload(completedUri, title, choice));
                result.delete();
            } catch (Exception error) {
                if (!isCancelled(error)) {
                    String message = friendly(error);
                    mainHandler.post(() -> failDownload(message));
                }
            } finally {
                downloading = false;
                processId = null;
            }
        });
    }

    private File findOutput(String output, File stage, String token) {
        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line.startsWith("__YTDESK_FILE__")) {
                    File printed = new File(line.substring("__YTDESK_FILE__".length()).trim());
                    if (printed.isFile()) return printed;
                }
            }
        }
        File[] files = stage.listFiles((dir, name) -> name.contains(token) && !name.endsWith(".part"));
        if (files == null || files.length == 0) return null;
        return files[0];
    }

    private Uri saveToDownloads(File source, String title, String id, boolean audio) throws Exception {
        String extension = audio ? ".mp3" : ".mp4";
        String displayName = safeFilename(title) + " [" + safeFilename(id) + "] " + System.currentTimeMillis() + extension;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, audio ? "audio/mpeg" : "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YtDesk");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri destination = getContentResolver().insert(collection, values);
        if (destination == null) throw new IllegalStateException("Android could not create the output file.");
        try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IllegalStateException("Android could not open the output file.");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(destination, ready, null, null);
            return destination;
        } catch (Exception error) {
            getContentResolver().delete(destination, null, null);
            throw error;
        }
    }

    private void updateProgress(float progress, long eta, String line) {
        if (!downloading) return;
        int percent = Math.max(0, Math.min(99, Math.round(progress)));
        downloadProgress.setProgress(percent);
        downloadPercent.setText(percent + "%");
        if (line != null && (line.toLowerCase(Locale.ROOT).contains("merge") || line.toLowerCase(Locale.ROOT).contains("post-process") || line.toLowerCase(Locale.ROOT).contains("extractaudio"))) {
            downloadStatus.setText("Processing media…");
            downloadProgress.setProgress(99);
            downloadPercent.setText("99%");
        } else {
            downloadStatus.setText("Downloading…");
        }
        downloadInfo.setText(eta > 0 ? "Estimated time remaining: " + formatDuration((int) eta) : "Saving to Downloads/YtDesk");
    }

    private void completeDownload(Uri uri, String title, FormatChoice choice) {
        lastSavedUri = uri;
        downloadProgress.setProgress(100);
        downloadPercent.setText("100%");
        downloadStatus.setText("Download completed");
        downloadInfo.setText("Saved to Downloads/YtDesk");
        cancelButton.setVisibility(View.GONE);
        completedActions.setVisibility(View.VISIBLE);
        addHistory(uri, title, choice.label + ("audio".equals(choice.kind) ? " MP3" : " MP4"));
        renderHistory();
        toast("Download completed.");
    }

    private void failDownload(String message) {
        downloadStatus.setText("Download failed");
        downloadInfo.setText(message);
        cancelButton.setVisibility(View.GONE);
        completedActions.setVisibility(View.GONE);
        toast(message);
    }

    private void cancelDownload() {
        String id = processId;
        if (id == null) return;
        cancelButton.setEnabled(false);
        downloadStatus.setText("Cancelling…");
        // Signal the active yt-dlp process directly; scheduling this on `worker`
        // would wait behind the download that needs cancelling.
        new Thread(() -> {
            boolean cancelled = YoutubeDL.getInstance().destroyProcessById(id);
            mainHandler.post(() -> {
                cancelButton.setEnabled(true);
                if (cancelled) {
                    downloadStatus.setText("Download cancelled");
                    downloadInfo.setText("Temporary media files will be cleaned up.");
                    cancelButton.setVisibility(View.GONE);
                } else {
                    downloadStatus.setText("Still stopping download…");
                    downloadInfo.setText("Wait a moment while the current media step stops.");
                }
            });
        }, "ytdesk-cancel").start();
    }

    private boolean isCancelled(Exception error) {
        return error.getClass().getSimpleName().contains("Canceled") ||
                (error.getMessage() != null && error.getMessage().toLowerCase(Locale.ROOT).contains("cancel"));
    }

    private void addHistory(Uri uri, String title, String quality) {
        try {
            JSONArray history = new JSONArray(preferences.getString(HISTORY_KEY, "[]"));
            JSONArray updated = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("uri", uri.toString());
            entry.put("title", title);
            entry.put("quality", quality);
            entry.put("date", System.currentTimeMillis());
            updated.put(entry);
            for (int i = 0; i < history.length() && i < 49; i++) updated.put(history.getJSONObject(i));
            preferences.edit().putString(HISTORY_KEY, updated.toString()).apply();
        } catch (Exception ignored) {
            toast("The file was saved, but its history entry could not be saved.");
        }
    }

    private void renderHistory() {
        historyList.removeAllViews();
        JSONArray history;
        try {
            history = new JSONArray(preferences.getString(HISTORY_KEY, "[]"));
        } catch (Exception error) {
            history = new JSONArray();
        }
        if (history.length() == 0) {
            TextView empty = text("No downloads yet", 14, R.color.yt_muted, false);
            empty.setPadding(dp(2), dp(8), dp(2), dp(8));
            historyList.addView(empty);
            return;
        }
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            MaterialCardView card = new MaterialCardView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(7);
            card.setLayoutParams(params);
            card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.yt_surface));
            card.setRadius(dp(13));
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(ContextCompat.getColor(this, R.color.yt_border));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView title = text(item.optString("title", "Downloaded media"), 14, R.color.yt_text, true);
            TextView subtitle = text(item.optString("quality", "Media") + "  ·  " + android.text.format.DateFormat.format("MMM d, h:mm a", item.optLong("date")), 12, R.color.yt_muted, false);
            row.addView(title);
            row.addView(subtitle);
            card.addView(row);
            card.setOnClickListener(view -> openUri(Uri.parse(item.optString("uri"))));
            historyList.addView(card);
        }
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(dp(20), dp(8), dp(20), dp(4));
        panel.setOrientation(LinearLayout.VERTICAL);
        SwitchMaterial metadataSwitch = new SwitchMaterial(this);
        metadataSwitch.setText("Embed title and channel metadata");
        metadataSwitch.setTextColor(ContextCompat.getColor(this, R.color.yt_text));
        metadataSwitch.setChecked(preferences.getBoolean(EMBED_METADATA_KEY, false));
        panel.addView(metadataSwitch);
        TextView folder = text("Downloads are saved in Downloads/YtDesk.", 13, R.color.yt_muted, false);
        folder.setPadding(0, dp(12), 0, dp(12));
        panel.addView(folder);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(panel)
                .setPositiveButton("Save", (dialog, which) -> preferences.edit().putBoolean(EMBED_METADATA_KEY, metadataSwitch.isChecked()).apply())
                .setNeutralButton("About", (dialog, which) -> showAbout())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("About YtDesk")
                .setMessage("YtDesk for Android · Version 1.0.0\n\nDeveloped by Ishu Raj. Save only content you have rights and permission to download. This app does not bypass DRM, private videos, or access controls. Follow YouTube’s Terms of Service and applicable law.")
                .setPositiveButton("Done", null)
                .show();
    }

    private void openSavedFile() {
        if (lastSavedUri != null) openUri(lastSavedUri);
    }

    private void openUri(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getContentResolver().getType(uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception error) {
            toast("No app is available to open this file.");
        }
    }

    private void openDownloads() {
        try {
            startActivity(new Intent("android.intent.action.VIEW_DOWNLOADS"));
        } catch (Exception error) {
            toast("Open Files and browse to Downloads/YtDesk.");
        }
    }

    private void setFormatsVisible(boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
        formatsTitle.setVisibility(state);
        if (!videoChoices.isEmpty()) videoFormatsLabel.setVisibility(state);
        if (!audioChoices.isEmpty()) audioFormatsLabel.setVisibility(state);
    }

    private TextView text(String value, int sizeSp, int colorId, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(ContextCompat.getColor(this, colorId));
        if (bold) text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        return text;
    }

    private String normalizeYouTubeUrl(String value) {
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("youtu.be") || host.equalsIgnoreCase("www.youtu.be") ||
                    host.equalsIgnoreCase("youtube.com") || host.equalsIgnoreCase("www.youtube.com") || host.equalsIgnoreCase("m.youtube.com") || host.equalsIgnoreCase("music.youtube.com"))) {
                throw new IllegalArgumentException("Enter a supported YouTube video link.");
            }
            String id = null;
            if (host.toLowerCase(Locale.ROOT).endsWith("youtu.be")) {
                List<String> segments = uri.getPathSegments();
                if (!segments.isEmpty()) id = segments.get(0);
            } else {
                String path = uri.getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) id = uri.getQueryParameter("v");
                else if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
                    String[] segments = path.split("/");
                    if (segments.length > 2) id = segments[2];
                }
            }
            if (id == null || !VIDEO_ID.matcher(id).matches()) throw new IllegalArgumentException("That YouTube link does not contain a valid video ID.");
            return "https://www.youtube.com/watch?v=" + id;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Enter a valid YouTube video link.");
        }
    }

    private String getSharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        return text == null ? null : text.toString().trim();
    }

    private String friendly(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Unable to complete this request. Check your connection and try again.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("private video") || lower.contains("members-only") || lower.contains("sign in") || lower.contains("login required") || lower.contains("age-restricted")) {
            return "This video is not publicly available. Private or access-controlled content is not supported.";
        }
        if (lower.contains("ffmpeg") || lower.contains("postprocess") || lower.contains("post-process")) return "Media processing failed. Restart the app and retry; if it continues, update YtDesk.";
        return message.replaceFirst("(?s)^.*?ERROR:\\s*", "").substring(0, Math.min(500, message.replaceFirst("(?s)^.*?ERROR:\\s*", "").length()));
    }

    private String safeFilename(String value) {
        String safe = value == null ? "YouTube video" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty()) safe = "YouTube video";
        return safe.substring(0, Math.min(100, safe.length()));
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) return "Duration unavailable";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder) : String.format(Locale.ROOT, "%d:%02d", minutes, remainder);
    }

    private String readableSize(long bytes) {
        if (bytes <= 0) return "Size varies";
        String[] units = {"B", "KB", "MB", "GB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && downloading && processId != null) YoutubeDL.getInstance().destroyProcessById(processId);
        super.onDestroy();
    }

    private static final class FormatChoice {
        final String label;
        final String value;
        final String kind;
        final String size;
        final int metric;

        FormatChoice(String label, String value, String kind, String size, int metric) {
            this.label = label;
            this.value = value;
            this.kind = kind;
            this.size = size;
            this.metric = metric;
        }
    }
}
