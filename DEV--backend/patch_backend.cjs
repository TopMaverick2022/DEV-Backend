const fs = require('fs');
const path = 'd:/xampp/htdocs/DeveloperEv/DEV-Backend/DEV--backend/src/main/java/com/developerev/service/AntiGravityService.java';
let content = fs.readFileSync(path, 'utf8');

const target1 = `        8. Auto-detect all the specific libraries, dependencies, framework modules, or tools needed for this feature and return them in "detectedNeeds".`;
const replacement1 = `        8. Auto-detect all the specific libraries, dependencies, framework modules, or tools needed for this feature and return them in "detectedNeeds".
        9. CRITICAL: For the "type" field of each task, you MUST use exactly one of the following predefined strings: "Backend", "Frontend", "Database", "Security", "Testing", "Documentation", "DevOps", "Architecture", "Design". Do NOT invent custom types like "Backend Configuration" or "Database Schema". Use these predefined exact strings ONLY.`;

const target2 = `             {
               "title": "",
               "description": "",
               "type": "",
               "estimatedHours": 0,
               "priority": ""
             }`;
const replacement2 = `             {
               "title": "",
               "description": "",
               "type": "Backend", // MUST be one of the predefined types
               "estimatedHours": 0,
               "priority": ""
             }`;

const target3 = `      }

      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());`;
const replacement3 = `      }

      for (com.developerev.model.Task task : tasks) {
        task.setStatus(com.developerev.model.TaskStatus.DONE);
        taskRepository.save(task);
      }

      activityLogService.logCurrentUserActivity(feature.getProjectId(), "Implemented Plan", "AI implemented codebase for feature: " + feature.getName());`;

if (content.includes(target1)) content = content.replace(target1, replacement1);
if (content.includes(target2)) content = content.replace(target2, replacement2);
if (content.includes(target3)) content = content.replace(target3, replacement3);

fs.writeFileSync(path, content, 'utf8');
console.log('Successfully patched AntiGravityService');
