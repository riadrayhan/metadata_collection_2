## MongoDB Setup Script - Run this after getting your connection string
param(
    [Parameter(Mandatory=$true)]
    [string]$MongoURI
)

Write-Host "`n=== Step 1: Testing MongoDB Connection ===" -ForegroundColor Cyan
$testResult = node -e "
const { MongoClient } = require('mongodb');
(async () => {
  try {
    const client = new MongoClient('$MongoURI');
    await client.connect();
    const db = client.db('datacollector');
    const collections = await db.listCollections().toArray();
    console.log('SUCCESS: Connected! Collections: ' + collections.length);
    await client.close();
  } catch(e) { console.log('FAIL: ' + e.message); process.exit(1); }
})();
" 2>&1
Write-Host $testResult
if ($LASTEXITCODE -ne 0) { Write-Host "Connection failed. Check your URI." -ForegroundColor Red; exit 1 }

Write-Host "`n=== Step 2: Adding MONGODB_URI to Vercel ===" -ForegroundColor Cyan
Push-Location "c:\Users\BM COMPUTERS\Documents\project\metadata_collection\backend"
vercel env rm MONGODB_URI production -y 2>$null
echo $MongoURI | vercel env add MONGODB_URI production
Write-Host "Done!" -ForegroundColor Green

Write-Host "`n=== Step 3: Deploying to Vercel ===" -ForegroundColor Cyan
vercel --prod --yes 2>&1
Write-Host "Deployed!" -ForegroundColor Green

Write-Host "`n=== Step 4: Seeding existing data to MongoDB ===" -ForegroundColor Cyan
Start-Sleep -Seconds 5
$seedResult = Invoke-RestMethod -Uri "https://backend-black-six-89.vercel.app/api/seed" -Method POST
Write-Host "Seeded: $($seedResult.message)" -ForegroundColor Green

Write-Host "`n=== Step 5: Verifying ===" -ForegroundColor Cyan
Start-Sleep -Seconds 3
$summary = Invoke-RestMethod -Uri "https://backend-black-six-89.vercel.app/api/summary" -Method GET
Write-Host "Call Logs: $($summary.total_call_logs)" -ForegroundColor Yellow
Write-Host "SMS: $($summary.total_sms)" -ForegroundColor Yellow
Write-Host "Devices: $($summary.devices)" -ForegroundColor Yellow

Write-Host "`n=== ALL DONE! ===" -ForegroundColor Green
Write-Host "Admin Panel: https://backend-black-six-89.vercel.app" -ForegroundColor Cyan
Pop-Location
