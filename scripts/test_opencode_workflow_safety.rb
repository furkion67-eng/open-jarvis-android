#!/usr/bin/env ruby
# Static policy regression test for the OpenCode autonomous workflow.
# This validates least privilege and fail-closed write gating without accessing secrets.
require "yaml"

path = ARGV.fetch(0, ".github/workflows/opencode-apk-repair.yml")
workflow = YAML.load_file(path)
jobs = workflow.fetch("jobs")

def require_policy(condition, message)
  abort("POLICY-FAIL: #{message}") unless condition
end

expected_read = {
  "contents" => "read",
  "pull-requests" => "read",
  "issues" => "read",
  "actions" => "read"
}
expected_preflight = { "contents" => "read", "actions" => "read" }
expected_develop = {
  "contents" => "write",
  "pull-requests" => "write",
  "issues" => "read",
  "actions" => "read"
}

require_policy(workflow.fetch("permissions") == expected_read,
               "workflow-level permissions must remain read-only")
require_policy(jobs.fetch("preflight").fetch("permissions") == expected_preflight,
               "preflight must be read-only")
require_policy(jobs.fetch("read-only-agent-smoke").fetch("permissions") == expected_read,
               "read-only smoke must be read-only")
require_policy(jobs.fetch("develop").fetch("permissions") == expected_develop,
               "only develop may receive narrowly scoped write permissions")

develop_if = jobs.fetch("develop").fetch("if").to_s
require_policy(develop_if.include?("main_protected == 'true'"),
               "develop must require the successful protected-main preflight")

ruleset_step = jobs.fetch("preflight").fetch("steps").find do |step|
  step.fetch("name", "").include?("PR-required no-bypass ruleset")
end
require_policy(!ruleset_step.nil?, "no-bypass ruleset preflight step must exist")
ruleset_script = ruleset_step.fetch("run", "")
[
  "rulesets?includes_parents=true",
  "bypass_actors",
  "required_approving_review_count",
  "enforcement",
  "refs/heads/main",
  "main.protected"
].each do |token|
  require_policy(ruleset_script.include?(token),
                 "ruleset preflight must verify #{token}")
end

read_only_job = jobs.fetch("read-only-agent-smoke")
read_only_text = read_only_job.fetch("steps").to_s
require_policy(!read_only_text.include?("GITHUB_TOKEN") &&
               !read_only_text.include?("secrets."),
               "read-only smoke must not receive tokens or repository secrets")

workflow_text = File.read(path)
require_policy(!workflow_text.match?(/^\s*issues:\s*write\s*$/),
               "issues:write is not permitted")
require_policy(!workflow_text.match?(/gh\s+pr\s+merge|git\s+push\s+origin\s+main/i),
               "workflow must not contain a merge or direct-main-push command")
require_policy(!workflow_text.match?(/(?:echo|printf|cat).*GOOGLE_GENERATIVE_AI_API_KEY/i),
               "workflow must not print the model API key variable")

puts "OPENCODE_WRITE_GATE_POLICY_OK"
