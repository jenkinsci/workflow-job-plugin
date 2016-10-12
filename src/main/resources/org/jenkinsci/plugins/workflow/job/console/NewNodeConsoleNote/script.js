Behaviour.specify("span.pipeline-new-node", 'NewNodeConsoleNote', 0, function(e) {
    if (e.getAttribute('startId') == null) {
        e.innerHTML = e.innerHTML.replace(/.+/, '$&<span class="pipeline-show-hide"> (<a href="#" onclick="showHidePipelineSection(this); return false">hide</a>)</span>')
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
    // TODO for a block node, look up other pipeline-new-node elements with parentIds including this (transitively) and mask them and their text too
    var sect = '.pipeline-node-' + id
    var ss = document.styleSheets[0]
    for (var i = 0; i < ss.rules.length; i++) {
        if (ss.rules[i].selectorText === sect) {
            ss.rules[i].style.display = display
            return
        }
    }
    // TODO order rules, so that hiding and reshowing a high-level section will restore expansion of a lower-level section
    ss.insertRule(sect + ' {display: ' + display + '}', ss.rules.length)
}
