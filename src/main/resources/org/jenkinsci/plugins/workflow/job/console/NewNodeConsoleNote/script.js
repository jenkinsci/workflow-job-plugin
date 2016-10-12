Behaviour.specify("span.pipeline-new-node", 'NewNodeConsoleNote', 0, function(e) {
    var startId = e.getAttribute('startId')
    if (startId == null || startId == e.getAttribute('nodeId')) {
        e.innerHTML = e.innerHTML.replace(/.+/, '$&<span class="pipeline-show-hide"> (<a href="#" onclick="showHidePipelineSection(this); return false">hide</a>)</span>')
        // TODO automatically hide second and subsequent branches: namely, in case a node has the same parent as an earlier one
    }
});

function showHidePipelineSection(link) {
    var span = link.parentNode.parentNode
    var id = span.getAttribute('nodeId')
    var display
    if (link.textContent === 'hide') {
        display = 'none'
        link.textContent = 'show'
        link.parentNode.className = ''
    } else {
        display = 'inline'
        link.textContent = 'hide'
        link.parentNode.className = 'pipeline-show-hide'
    }
    var showHide = function(id, display) {
        var sect = '.pipeline-node-' + id
        var ss = document.styleSheets[0]
        for (var i = 0; i < ss.rules.length; i++) {
            if (ss.rules[i].selectorText === sect) {
                ss.rules[i].style.display = display
                return
            }
        }
        ss.insertRule(sect + ' {display: ' + display + '}', ss.rules.length)
    }
    showHide(id, display)
    if (span.getAttribute('startId') != null) {
        // For a block node, look up other pipeline-new-node elements parented to this (transitively) and mask them and their text too.
        var nodes = $$('.pipeline-new-node')
        var ids = []
        var parents = new Map() // id → [parents]
        var starts = new Map()
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i]
            var oid = node.getAttribute('nodeId')
            ids.push(oid)
            parents.set(oid, node.getAttribute('parentIds').split(' '))
            starts.set(oid, node.getAttribute('startId'))
        }
        // Cf. FlowScanningUtils.fetchEnclosingBlocks
        var encloses = function(enclosing, enclosed, parents, starts) {
            var id = enclosed
            while (true) {
                if (id == enclosing) {
                    return true // found it
                }
                var start = starts.get(id)
                if (start != null && start != id) {
                    id = start
                    continue // block end node
                }
                var ps = parents.get(id)
                if (/* currently FlowStartNode is not represented */ ps == null || ps.length == 0) {
                    return false // hit flow start node
                }
                id = ps[0] // length > 1 only for parallel block end node, which we already handled
                // continue looking for earlier siblings and/or enclosing nodes
            }
        }
        for (var i = 0; i < ids.length; i++) {
            var oid = ids[i]
            if (oid == id) {
                continue
            } else if (encloses(id, oid, parents, starts)) {
                showHide(oid, display)
                var header = $$('.pipeline-new-node[nodeId=' + oid + ']')
                if (header.length > 0) {
                    header[0].style.display = display
                }
                if (display == 'inline') {
                    // Mark all children as shown. TODO would be nicer to leave them collapsed if they were before, but this gets complicated.
                    var link = $$('.pipeline-new-node[nodeId=' + oid + '] span a')
                    if (link.length > 0) {
                        link[0].textContent = 'hide'
                        link[0].parentNode.className = 'pipeline-show-hide'
                    }
                }
            }
        }
    }
}
