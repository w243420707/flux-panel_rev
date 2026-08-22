package com.admin.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * 托管节点安装脚本和各架构节点二进制。
 * 这些资源由 deploy compose 从仓库目录只读挂载，不依赖 GitHub 下载。
 */
@RestController
@CrossOrigin
public class NodeAssetController {

    private static final Set<String> RELEASE_NAMES = Set.of(
            "SHA256SUMS",
            "gost-linux-amd64",
            "gost-linux-arm64",
            "gost-linux-armv7",
            "gost-linux-armv6"
    );

    private final Path assetRoot;

    public NodeAssetController() {
        String configuredRoot = System.getenv("NODE_ASSETS_DIR");
        if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
            configuredRoot = "/opt/flux-panel/node-assets";
        }
        this.assetRoot = Paths.get(configuredRoot).toAbsolutePath().normalize();
    }

    @GetMapping(value = "/node/install.sh", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Resource> installScript() {
        return serveFile(assetRoot.resolve("install.sh"), MediaType.TEXT_PLAIN);
    }

    @GetMapping("/node/releases/{name}")
    public ResponseEntity<Resource> release(@PathVariable String name) {
        if (!RELEASE_NAMES.contains(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        MediaType contentType = "SHA256SUMS".equals(name)
                ? MediaType.TEXT_PLAIN
                : MediaType.APPLICATION_OCTET_STREAM;
        return serveFile(assetRoot.resolve("releases").resolve(name), contentType);
    }

    private ResponseEntity<Resource> serveFile(Path file, MediaType contentType) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(assetRoot) || !Files.isRegularFile(normalized)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FileSystemResource resource = new FileSystemResource(normalized);
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }
}
