"""Reject duplicate YAML keys and invalid job dependency references before pushing CI."""
import json
from pathlib import Path
import re
import yaml

ROOT=Path(__file__).resolve().parents[1]


class UniqueLoader(yaml.SafeLoader):
    def construct_mapping(self,node,deep=False):
        self.flatten_mapping(node)
        result={}
        for key_node,value_node in node.value:
            key=self.construct_object(key_node,deep=deep)
            if key in result:
                raise ValueError(f'Duplicate YAML key {key!r} at line {key_node.start_mark.line+1}')
            result[key]=self.construct_object(value_node,deep=deep)
        return result


def validate(text):
    workflow=yaml.load(text,Loader=UniqueLoader)
    jobs=workflow.get('jobs',{})
    dependencies={}
    for name,job in jobs.items():
        needs=job.get('needs',[])
        if isinstance(needs,str):needs=[needs]
        if not isinstance(needs,list) or any(not isinstance(n,str) for n in needs):
            raise ValueError(f'{name}: needs must be a job name or list of names')
        if name in needs or any(n not in jobs for n in needs):
            raise ValueError(f'{name}: self or missing job dependency')
        expressions=re.findall(r'\$\{\{(.*?)\}\}',json.dumps(job),flags=re.S)
        references={name for expression in expressions for name in re.findall(r'\bneeds\.([A-Za-z0-9_-]+)',expression)}
        if references-set(needs):
            raise ValueError(f'{name}: undeclared needs reference: {sorted(references-set(needs))}')
        dependencies[name]=needs
    visiting=set();done=set()
    def visit(name):
        if name in visiting:raise ValueError(f'Cyclic job dependency at {name}')
        if name in done:return
        visiting.add(name)
        for other in dependencies[name]:visit(other)
        visiting.remove(name);done.add(name)
    for name in jobs:visit(name)
    return workflow


if __name__=='__main__':
    files=sorted((ROOT/'.github/workflows').glob('*.y*ml'))
    for path in files:
        try:validate(path.read_text(encoding='utf-8'))
        except Exception as error:raise SystemExit(f'{path.name}: {error}') from error
    print(f'Workflow YAML keys and job dependencies checked: {len(files)} files. GitHub execution is still required.')
