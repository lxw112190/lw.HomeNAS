const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const context=vm.createContext({});vm.runInContext(fs.readFileSync(path.join(__dirname,'../android/app/src/main/assets/www/picker-selection.js'),'utf8')+';globalThis.Selection=DailySelection',context);
const s=new context.Selection();s.day('2026-11-05',true);assert(s.has('2026-11-05','later-page'));
s.item('2026-11-05','cancelled',false);assert(!s.has('2026-11-05','cancelled'));assert(s.has('2026-11-05','later-page'));assert(s.partial('2026-11-05'));
assert.deepEqual(JSON.parse(JSON.stringify(s.payload())),{days:['2026-11-05'],ids:[],exclude_ids:['cancelled']});
s.item('2026-11-05','cancelled',true);assert(s.has('2026-11-05','cancelled'));assert(!s.partial('2026-11-05'));
s.item('2026-11-05','cancelled',false);s.day('2026-11-05',true);assert.equal(s.excluded.size,0);
s.day('2026-11-05',false);assert(!s.has('2026-11-05','later-page'));
s.item('2026-11-05','single',true);assert(s.partial('2026-11-05'));s.day('2026-11-05',true);assert.equal(s.items.size,0);
s.item('2026-11-04','another-day',true);s.item('2026-11-05','cancelled',false);s.day('2026-11-05',false);assert(s.items.has('another-day'));assert.equal(s.excluded.size,0);
s.clear();assert.equal(s.items.size+s.days.size+s.excluded.size,0);
console.log('Daily selection: whole days, exceptions, later pages, reselect and reset passed');

s.month('2026-11',true);assert(s.has('2026-11-30','later-month-page'));assert(!s.has('2026-10-31','outside'));
s.day('2026-11-06',false);assert(!s.has('2026-11-06','excluded-day'));assert(s.has('2026-11-07','kept-day'));
s.day('2026-11-06',true);s.item('2026-11-06','one',false);assert(!s.has('2026-11-06','one'));assert(s.has('2026-11-06','two'));
s.allItems(true);s.month('2026-11',false);assert(!s.has('2026-11-07','excluded-month'));assert(s.has('2026-10-31','kept-month'));
s.day('2026-11-06',true);assert(s.has('2026-11-06','restored-day'));assert(s.has('unknown','undated'));
assert(JSON.parse(JSON.stringify(s.payload())).exclude_months.includes('2026-11'));
s.allItems(false);assert(!s.hasSelection());
console.log('Month/all selection: cross-page ranges, excluded months/days/items, day override and clear passed');

s.clear();for(let i=0;i<5000;i++)s.item('2026-11-05','bulk'+i,true);assert(s.partial('2026-11-05'));assert(s.partialMonth('2026-11'));const cached=s.partialItems;for(let i=0;i<1000;i++)assert(s.partial('2026-11-05'));assert.equal(s.partialItems,cached);s.month('2026-11',false);assert(!s.partial('2026-11-05'));assert(!s.partialMonth('2026-11'));s.day('2026-11-05',true);s.item('2026-11-05','excluded',false);assert(s.partial('2026-11-05'));s.day('2026-11-05',false);assert(!s.partial('2026-11-05'));console.log('Large-selection partial-state index: 5000 items, cached repeated lookups and range invalidation passed');
