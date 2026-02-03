package com.myco.custom.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;

public class TextViewRangeWebScript extends AbstractWebScript {

    private ContentService contentService;
    private NodeService nodeService;

    public void setContentService(ContentService contentService) { this.contentService = contentService; }
    public void setNodeService(NodeService nodeService) { this.nodeService = nodeService; }

    // Parse: "bytes=START-END" or "bytes=START-" or "bytes=-SUFFIX"
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    @Override
    public void execute(WebScriptRequest req, WebScriptResponse res) throws IOException {

        String nodeRefStr = req.getParameter("nodeRef");
        if (nodeRefStr == null || nodeRefStr.isBlank()) {
            res.setStatus(400);
            res.getWriter().write("{\"error\":\"nodeRef parameter is required\"}");
            return;
        }

        NodeRef nodeRef = new NodeRef(nodeRefStr);
        if (!nodeService.exists(nodeRef)) {
            res.setStatus(404);
            res.getWriter().write("{\"error\":\"nodeRef not found\"}");
            return;
        }

        ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
        if (reader == null || !reader.exists()) {
            res.setStatus(404);
            res.getWriter().write("{\"error\":\"content missing\"}");
            return;
        }

        long total = reader.getSize();

        // Default window if client doesn't send Range:
        long start = 0;
        long end = Math.min(total - 1, 1024 * 1024 - 1); // first 1MB

        String rangeHeader = req.getHeader("Range");
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            Matcher m = RANGE.matcher(rangeHeader.trim());
            if (!m.matches()) {
                res.setStatus(416);
                res.setHeader("Content-Range", "bytes */" + total);
                return;
            }
            String a = m.group(1);
            String b = m.group(2);

            // bytes=-SUFFIX
            if (a.isEmpty() && !b.isEmpty()) {
                long suffix = Long.parseLong(b);
                suffix = Math.min(suffix, total);
                start = total - suffix;
                end = total - 1;
            } else {
                start = a.isEmpty() ? 0 : Long.parseLong(a);
                end = b.isEmpty() ? (start + (1024 * 1024) - 1) : Long.parseLong(b);
            }

            if (start < 0 || start >= total) {
                res.setStatus(416);
                res.setHeader("Content-Range", "bytes */" + total);
                return;
            }
            end = Math.min(end, total - 1);
            if (end < start) {
                res.setStatus(416);
                res.setHeader("Content-Range", "bytes */" + total);
                return;
            }
        }

        // Make boundaries safer for text: expand slightly so we can trim to newline
        // (avoid breaking in middle of UTF-8 / line)
        long extra = 4096;
        long safeStart = Math.max(0, start - extra);
        long safeEnd = Math.min(total - 1, end + extra);

        long safeLen = safeEnd - safeStart + 1;

        res.setStatus(rangeHeader != null ? 206 : 200);
        res.setContentType("text/plain; charset=utf-8");
        res.setHeader("Accept-Ranges", "bytes");
        res.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        res.setHeader("Cache-Control", "no-store");
        // If you're behind nginx, also ensure proxy_buffering off there.

        // Stream bytes
        try (InputStream is = reader.getContentInputStream();
             OutputStream os = res.getOutputStream()) {

            // Skip to safeStart
            skipFully(is, safeStart);

            byte[] buf = new byte[64 * 1024];
            long remaining = safeLen;
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int)Math.min(safeLen, 2 * 1024 * 1024));

            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = is.read(buf, 0, toRead);
                if (read < 0) break;
                baos.write(buf, 0, read);
                remaining -= read;
            }

            // Decode and trim to newline around requested [start,end]
            String chunk = baos.toString(StandardCharsets.UTF_8);

            // Trim leading partial line if start > 0
            if (start > 0) {
                int firstNl = chunk.indexOf('\n');
                if (firstNl >= 0) chunk = chunk.substring(firstNl + 1);
            }

            // Trim trailing partial line if end < total-1
            if (end < total - 1) {
                int lastNl = chunk.lastIndexOf('\n');
                if (lastNl >= 0) chunk = chunk.substring(0, lastNl + 1);
            }

            os.write(chunk.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static void skipFully(InputStream is, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped <= 0) {
                if (is.read() == -1) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
