package ru.sociometric.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sociometric.service.AdminService;

import java.nio.charset.StandardCharsets;

/** Maintenance endpoints for a single-school installation. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/backup")
    public ResponseEntity<byte[]> backup() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("sociometric-backup.zip", StandardCharsets.UTF_8).build().toString())
                .body(admin.backup());
    }

    @DeleteMapping("/data")
    public ResponseEntity<Void> erase(@RequestParam String confirm) {
        if (!"УДАЛИТЬ ВСЕ ДАННЫЕ".equals(confirm)) {
            throw new BadRequestException("Для удаления передайте точное подтверждение «УДАЛИТЬ ВСЕ ДАННЫЕ»");
        }
        admin.eraseAllData();
        return ResponseEntity.noContent().build();
    }
}
