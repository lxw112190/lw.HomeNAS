module.exports = async ({github, context, core}) => {
  const {owner, repo} = context.repo;
  const dispatch = context.eventName === 'workflow_dispatch';
  const publish = !dispatch || core.getInput('publish') !== 'false';
  const input = dispatch ? core.getInput('android_run_id') : String(context.payload.workflow_run.id);
  if (input && (!/^[1-9][0-9]*$/.test(input) || !Number.isSafeInteger(Number(input)))) throw new Error('Android run ID must be a positive safe integer.');
  const valid = run => run.name === 'Android APK' && run.path === '.github/workflows/android.yml' && run.head_branch === 'main' && run.head_repository?.full_name === `${owner}/${repo}` && ['push','workflow_dispatch'].includes(run.event) && run.status === 'completed' && run.conclusion === 'success' && /^[a-f0-9]{40}$/.test(run.head_sha);
  const candidates = input ? [(await github.rest.actions.getWorkflowRun({owner,repo,run_id:Number(input)})).data] : (await github.rest.actions.listWorkflowRuns({owner,repo,workflow_id:'android.yml',branch:'main',status:'success',per_page:30})).data.workflow_runs;
  let selected;
  for (const run of candidates) {
    if (!valid(run)) { if (input) throw new Error('Only successful trusted main-branch Android APK runs may be packaged.'); continue; }
    const jobs = await github.paginate(github.rest.actions.listJobsForWorkflowRun,{owner,repo,run_id:run.id,filter:'latest',per_page:100});
    if (['apk','emulator'].every(name => jobs.some(job => job.name === name && job.conclusion === 'success'))) { selected=run; break; }
    if (input) throw new Error('APK build and emulator jobs must both pass before release.');
  }
  if (!selected) throw new Error('No eligible Android run found. Run Android APK on main with emulator enabled first.');
  const name = `lw.HomeNAS-Android-${selected.run_number}`;
  const tag = `ci-${selected.run_number}`;
  let already=false;
  if (publish) {
    try {
      const release=(await github.rest.repos.getReleaseByTag({owner,repo,tag})).data;
      if (release.target_commitish !== selected.head_sha) throw new Error('Existing release tag points at a different commit.');
      already=!release.draft;
      if (already) core.notice(`Already published: ${release.html_url}. Existing assets will not be overwritten.`);
    } catch(error) { if(error.status !== 404) throw error; }
  }
  if(!already){
    const artifacts = await github.paginate(github.rest.actions.listWorkflowRunArtifacts,{owner,repo,run_id:selected.id,per_page:100});
    const matching = artifacts.filter(artifact => artifact.name === name && !artifact.expired);
    if(matching.length !== 1 || matching[0].workflow_run?.head_sha !== selected.head_sha)throw new Error('Expected one unexpired APK artifact from the selected commit.');
  }
  for(const [key,value] of Object.entries({run_id:selected.id,run_number:selected.run_number,sha:selected.head_sha,artifact_name:name,tag,publish:String(publish),already:String(already)}))core.setOutput(key,String(value));
};
