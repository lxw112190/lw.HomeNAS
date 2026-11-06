'use strict';
/* A whole day is selected lazily; exceptions also apply to later native pages. */
class DailySelection {
 constructor(){this.days=new Set();this.items=new Map();this.excluded=new Map();this.months=new Set();this.excludedDays=new Set();this.excludedMonths=new Set();this.all=false;}
 clear(){this.days.clear();this.items.clear();this.excluded.clear();this.months.clear();this.excludedDays.clear();this.excludedMonths.clear();this.all=false;}
 monthOf(date){return date==='unknown'?date:date.slice(0,7);}
 range(date){const month=this.monthOf(date);return this.days.has(date)||!this.excludedDays.has(date)&&!this.excludedMonths.has(month)&&(this.all||this.months.has(month));}
 allItems(checked){this.clear();this.all=checked;}
 month(month,checked){for(const set of [this.days,this.excludedDays])for(const date of set)if(this.monthOf(date)===month)set.delete(date);for(const map of [this.items,this.excluded])for(const [id,date] of map)if(this.monthOf(date)===month)map.delete(id);if(checked){this.excludedMonths.delete(month);this.months.add(month);}else{this.months.delete(month);if(this.all)this.excludedMonths.add(month);}}
 day(date,checked){for(const map of [this.items,this.excluded])for(const [id,d] of map)if(d===date)map.delete(id);if(checked){this.excludedDays.delete(date);this.days.add(date);}else{this.days.delete(date);this.excludedDays.add(date);}}
 item(date,id,checked){if(this.range(date)){if(checked)this.excluded.delete(id);else this.excluded.set(id,date);}else{if(checked)this.items.set(id,date);else this.items.delete(id);}}
 has(date,id){return this.range(date)?!this.excluded.has(id):this.items.has(id);}
 partial(date){return this.range(date)?[...this.excluded.values()].includes(date):[...this.items.values()].includes(date);}
 hasSelection(){return this.all||this.months.size||this.days.size||this.items.size;}
 payload(){const data={days:[...this.days],ids:[...this.items.keys()],exclude_ids:[...this.excluded.keys()]};if(this.all)data.all=true;if(this.months.size)data.months=[...this.months];if(this.excludedDays.size)data.exclude_days=[...this.excludedDays];if(this.excludedMonths.size)data.exclude_months=[...this.excludedMonths];return data;}
}
