'use strict';
/* A whole day is selected lazily; exceptions also apply to later native pages. */
class DailySelection {
 constructor(){this.days=new Set();this.items=new Map();this.excluded=new Map();}
 clear(){this.days.clear();this.items.clear();this.excluded.clear();}
 day(date,checked){for(const map of [this.items,this.excluded])for(const [id,d] of map)if(d===date)map.delete(id);if(checked)this.days.add(date);else this.days.delete(date);}
 item(date,id,checked){if(this.days.has(date)){if(checked)this.excluded.delete(id);else this.excluded.set(id,date);}else{if(checked)this.items.set(id,date);else this.items.delete(id);}}
 has(date,id){return this.days.has(date)?!this.excluded.has(id):this.items.has(id);}
 partial(date){return this.days.has(date)?[...this.excluded.values()].includes(date):[...this.items.values()].includes(date);}
 payload(){return {days:[...this.days],ids:[...this.items.keys()],exclude_ids:[...this.excluded.keys()]};}
}
