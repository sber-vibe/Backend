package org.example.subsidy;

import jakarta.validation.Valid;
import java.security.Principal;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.example.subsidy.Models.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final CheckSettings settings;
    private final ApplicationService applications;
    public AdminController(CheckSettings settings, ApplicationService applications) {
        this.settings = settings; this.applications = applications;
    }
    @GetMapping("/programs") public List<Program> programs() { return settings.programs(false); }
    @PostMapping("/programs") public ResponseEntity<Program> create(@Valid @RequestBody Program program, Principal actor) {
        var created = settings.saveProgram(program.code(), program, true, name(actor));
        return ResponseEntity.created(URI.create("/api/v1/admin/programs/" + created.code())).body(created);
    }
    @PutMapping("/programs/{code}") public Program update(@PathVariable String code, @Valid @RequestBody Program program, Principal actor) {
        return settings.saveProgram(code, program, false, name(actor));
    }
    @GetMapping("/rules") public List<Rule> rules() { return settings.rules(); }
    @PutMapping("/rules/{code}") public Rule updateRule(@PathVariable String code, @Valid @RequestBody Rule rule, Principal actor) {
        return settings.saveRule(code, rule, name(actor));
    }
    @GetMapping("/journal") public List<JournalEntry> journal(@RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) java.time.Instant before, @RequestParam(required = false) String action) {
        return java.util.stream.Stream.concat(settings.journal(limit, before, action).stream(), applications.allJournal(limit, before, action).stream())
                .sorted(java.util.Comparator.comparing(JournalEntry::at).reversed()).limit(Math.max(1, Math.min(limit, 1000))).toList();
    }
    private static String name(Principal principal) { return principal == null ? null : principal.getName(); }
}
