// Plan this run only. Job dependencies, not a search for old APKs, gate publication.
module.exports = async ({github, context, core}) => {
  const {owner, repo} = context.repo;
  if (!['push', 'workflow_dispatch'].includes(context.eventName) || context.ref !== 'refs/heads/main')
    throw new Error('Only trusted main-branch push or manual runs may produce a full release.');
  const {data: run} = await github.rest.actions.getWorkflowRun({owner, repo, run_id: context.runId});
  if (run.id !== context.runId || run.name !== 'Build APK, server and Release'
      || run.path !== '.github/workflows/android.yml' || run.head_branch !== 'main'
      || run.head_repository?.full_name !== `${owner}/${repo}` || run.event !== context.eventName
      || !/^[a-f0-9]{40}$/.test(run.head_sha) || run.head_sha !== context.sha
      || !Number.isSafeInteger(run.run_number) || run.run_number < 1 || run.run_number > 2146483647)
    throw new Error('Unexpected source workflow, repository, CI number or commit.');
  const versionFile = (await github.rest.repos.getContent({owner,repo,path:'VERSION',ref:run.head_sha})).data;
  if (versionFile.type !== 'file' || versionFile.encoding !== 'base64')
    throw new Error('VERSION must be a regular UTF-8 source file.');
  const version = Buffer.from(versionFile.content,'base64').toString('utf8').trim();
  if (!/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?$/.test(version))
    throw new Error('Invalid semantic VERSION.');
  const tag = `v${version}`;
  let publish = context.eventName !== 'workflow_dispatch' || core.getInput('publish') !== 'false';
  if (publish) {
    try {
      const release = (await github.rest.repos.getReleaseByTag({owner,repo,tag})).data;
      if (!release.draft) {
        publish = false;
        core.notice(`Version ${version} is already published. Building artifacts only; existing release remains unchanged.`);
      } else if (release.target_commitish !== run.head_sha) {
        throw new Error('Unpublished version draft belongs to a different commit; resolve it before retrying.');
      }
    } catch (error) { if (error.status !== 404) throw error; }
  }
  for (const [key,value] of Object.entries({run_id:run.id,run_number:run.run_number,sha:run.head_sha,
    artifact_name:`lw.HomeNAS-Android-${run.run_number}`,server_artifact_name:`lw.HomeNAS-server-ci${run.run_number}`,
    tag,version,publish:String(publish)})) core.setOutput(key,String(value));
};
