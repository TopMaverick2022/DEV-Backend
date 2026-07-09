const fs = require('fs');
const path = 'd:/xampp/htdocs/DeveloperEv/DEV-Backend/DEV--backend/src/main/java/com/developerev/service/AntiGravityService.java';
let content = fs.readFileSync(path, 'utf8');

const t1 = '8. Auto-detect all the specific libraries, dependencies, framework modules, or tools needed for this feature and return them in "detectedNeeds".';
const r1 = t1 + '\n        9. CRITICAL: For the "type" field of each task, you MUST use exactly one of the following predefined strings: "Backend", "Frontend", "Database", "Security", "Testing", "Documentation", "DevOps", "Architecture", "Design". Do NOT invent custom types like "Backend Configuration" or "Database Schema". Use these predefined exact strings ONLY.';

const t2 = '"type": "",';
const r2 = '"type": "Backend", // MUST be one of the predefined types';

const t3 = `      }

      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());`;
const r3 = `      }

      for (com.developerev.model.Task task : tasks) {
        task.setStatus(com.developerev.model.TaskStatus.DONE);
        taskRepository.save(task);
      }

      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());`;

let changed = false;
if (content.indexOf(t1) !== -1) {
  content = content.replace(t1, r1);
  changed = true;
}
if (content.indexOf(t2) !== -1) {
  content = content.replace(t2, r2);
  changed = true;
}
if (content.indexOf(t3) !== -1) {
  content = content.replace(t3, r3);
  changed = true;
}

if(changed) {
    fs.writeFileSync(path, content, 'utf8');
    console.log('Success string replace');
} else {
    console.log('No matches found');
}
