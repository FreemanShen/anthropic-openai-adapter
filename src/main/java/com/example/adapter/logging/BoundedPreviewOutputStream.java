package com.example.adapter.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes through to the target output stream while retaining a bounded preview for logs.
 */
public class BoundedPreviewOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final ByteArrayOutputStream previewBuffer = new ByteArrayOutputStream();
    private final int maxPreviewBytes;

    private long totalBytesWritten;

    public BoundedPreviewOutputStream(OutputStream delegate, int maxPreviewBytes) {
        this.delegate = delegate;
        this.maxPreviewBytes = maxPreviewBytes;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        totalBytesWritten++;
        if (previewBuffer.size() < maxPreviewBytes) {
            previewBuffer.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        totalBytesWritten += len;

        int remainingBytes = maxPreviewBytes - previewBuffer.size();
        if (remainingBytes > 0) {
            previewBuffer.write(b, off, Math.min(len, remainingBytes));
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    public boolean hasCapturedContent() {
        return totalBytesWritten > 0;
    }

    public String preview(String characterEncoding) {
        String preview = LogSanitizer.bodyPreview(previewBuffer.toByteArray(), characterEncoding);
        if (totalBytesWritten > previewBuffer.size()) {
            return preview + "...(truncated," + totalBytesWritten + " bytes)";
        }
        return preview;
    }
}
