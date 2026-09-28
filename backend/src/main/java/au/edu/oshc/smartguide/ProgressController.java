package au.edu.oshc.smartguide;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

@RestController
@RequestMapping("/api/progress")
class ProgressController {
    final ProgressRepository repository; final UserRepository users; final ObjectMapper mapper;
    ProgressController(ProgressRepository repository,UserRepository users,ObjectMapper mapper){this.repository=repository;this.users=users;this.mapper=mapper;}

    User current(Authentication auth){if(auth==null||!auth.isAuthenticated())return null;try{return users.findById(Long.valueOf(auth.getName())).orElse(null);}catch(Exception e){return null;}}

    @GetMapping ResponseEntity<?> get(Authentication auth){
        User u=current(auth);if(u==null)return ResponseEntity.status(401).body(Map.of("message","Authentication required."));
        String json=repository.findByEmail(u.getEmail()).map(Progress::getJson).orElse("{\"completedScenarioIds\":[],\"quizBestScore\":0,\"quizAttempts\":0}");
        try{return ResponseEntity.ok(mapper.readTree(json));}catch(Exception e){return ResponseEntity.ok(Map.of("completedScenarioIds",java.util.List.of(),"quizBestScore",0,"quizAttempts",0));}
    }

    @PutMapping ResponseEntity<?> put(@RequestBody String json,Authentication auth){
        User u=current(auth);if(u==null)return ResponseEntity.status(401).body(Map.of("message","Authentication required."));
        try{mapper.readTree(json);}catch(Exception e){return ResponseEntity.badRequest().body(Map.of("message","Invalid progress data."));}
        Progress p=repository.findByEmail(u.getEmail()).orElseGet(Progress::new);p.setEmail(u.getEmail());p.setJson(json);repository.save(p);return ResponseEntity.noContent().build();
    }
}
