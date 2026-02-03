package com.myco.custom.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.*;
import org.alfresco.service.namespace.QName;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.*;

public class TextSearchWebScript extends AbstractWebScript {

    private ContentService contentService;
    private NodeService nodeService;

    public void setContentService(ContentService contentService) { this.contentService = contentService; }
    public void setNodeService(NodeService nodeService) { this.nodeService = nodeService; }

    @Override
    public void execute(WebScriptRequest req, WebScriptResponse res) throws IOException {

        String nodeRefStr = req.getParameter("nodeRef");
        String q = req.getParameter("q");

        if (nodeRefStr == null || nodeRefStr.isBlank() || q == null || q.isBlank()) {
            res.setStatus(400);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"nodeRef and q are required\"}");
            return;
        }

        int maxHits = parseInt(req.getParameter("maxHits"), 100);
        maxHits = Math.max(1, Math.min(maxHits, 1000));

        long startOffset = parseLong(req.getParameter("startOffset"), 0);

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
        startOffset = Math.max(0, Math.min(startOffset, Math.max(0, total - 1)));

        JSONObject out = new JSONObject();
        out.put("q", q);
        out.put("totalBytes", total);
        out.put("startOffset", startOffset);

        JSONArray hits = new JSONArray();

        // Stream line by line from startOffset (best-effort: startOffset is byte-based)
        try (InputStream is = reader.getContentInputStream()) {

            skipFully(is, startOffset);

            // Read bytes -> decode as UTF-8 with buffered reader
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 64 * 1024);

            long approxOffset = startOffset;
            String line;

            while ((line = br.readLine()) != null && hits.length() < maxHits) {
                int idx = line.indexOf(q);
                if (idx >= 0) {
                    // offset is approximate (byte offset at line start + char index)
                    // good enough to jump near the match; the viewer trims to newlines anyway
                    JSONObject hit = new JSONObject();
                    hit.put("offset", approxOffset);
                    hit.put("snippet", makeSnippet(line, q, 80));
                    hits.put(hit);
                }

                // Approximate byte counting (UTF-8) per line + '\n'
                approxOffset += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (approxOffset >= total) break;
            }

            out.put("nextOffset", Math.min(approxOffset, total));
        }

        out.put("hits", hits);

        res.setStatus(200);
        res.setContentType("application/json; charset=utf-8");
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write(out.toString());
    }

    private static String makeSnippet(String line, String q, int maxLen) {
        int i = line.indexOf(q);
        if (i < 0) return line.length() <= maxLen ? line : line.substring(0, maxLen) + "...";
        int start = Math.max(0, i - maxLen / 2);
        int end = Math.min(line.length(), start + maxLen);
        String s = line.substring(start, end);
        if (start > 0) s = "..." + s;
        if (end < line.length()) s = s + "...";
        return s;
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); } catch (Exception e) { return def; }
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
