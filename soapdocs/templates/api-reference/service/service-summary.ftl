<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<html>
<head>

<!-- Generated by ${build.version} on ${build.date} -->
<title>
Zimbra SOAP API Reference ${build.version}
</title>

<LINK REL ="stylesheet" TYPE="text/css" HREF="../stylesheet.css" TITLE="Style">

<script type="text/javascript">
function windowTitle()
{
    parent.document.title="Service ${service.name} (Zimbra SOAP API Reference ${build.version})";
}
</script>

</head>

<BODY BGCOLOR="white" onload="windowTitle();">

<table cellspacing="3" cellpadding="0" border="0" summary="" bgcolor="#eeeeff">
  <tbody>
  <tr valign="top" align="center">
  <td bgcolor="#ffffff" class="NavBarCell1"> <a href="../overview-summary.html"><font class="NavBarFont1"><b>Overview</b></font></a>&nbsp;</td>
  <td bgcolor="#eeeeff" class="NavBarCell1Rev">    &nbsp;<font class="NavBarFont1Rev"><b>Service</b></font>&nbsp;</td>
  <td bgcolor="#eeeeff" class="NavBarCell1">    <font class="NavBarFont1">Command</font>&nbsp;</td>
  </tr>
  </tbody>
</table>

<HR>
<H2>
Service ${service.name}
</H2>

<p>
<TABLE BORDER="1" WIDTH="100%" CELLPADDING="3" CELLSPACING="0" SUMMARY="">
<TR BGCOLOR="#CCCCFF" CLASS="TableHeadingColor">
<TH ALIGN="left" COLSPAN="2"><FONT SIZE="+2">
<B>Command Summary</B></FONT></TH>
</TR>
<#list service.commands as command>
<TR BGCOLOR="white" CLASS="TableRowColor">
<TD WIDTH="20%"><B><A HREF="./${command.name}.html">${command.name}</A></B></TD>
<TD>${command.shortDescription}</TD>
</TR>
</#list>
</TABLE>
</p>

<p>
Copyright 2012 Zimbra, Inc. All rights reserved.
</p>

</body>
</html>
