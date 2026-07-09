const fs = require('fs');
const path = 'd:/xampp/htdocs/DeveloperEv/DEV-Backend/DEV--backend/src/main/java/com/developerev/service/AntiGravityService.java';
let content = fs.readFileSync(path, 'utf8');

const t1 = content.includes('8. Auto-detect');
const t2 = content.includes('"type": "",');
const t3 = content.includes('activityLogService.logCurrentUserActivity');

console.log('t1:', t1, 't2:', t2, 't3:', t3);
